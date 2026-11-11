package com.nexvio.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Syncs viewing-profile metadata and profile-scoped app preferences through Supabase.
 * The Supabase RLS policies are the security boundary; requests use only the signed-in
 * user's access token and the client publishable key.
 */
internal object NexvioCloudSync {
    data class Result(val profilesSynced: Int, val settingsSynced: Int, val message: String)

    suspend fun sync(context: Context, accessToken: String): Result = withContext(Dispatchers.IO) {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        require(base.isNotBlank() && apiKey.isNotBlank()) { "Supabase is not configured." }

        fun request(path: String, method: String = "GET", body: String? = null, prefer: String? = null): String {
            val connection = URL("$base/$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("apikey", apiKey)
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                if (prefer != null) connection.setRequestProperty("Prefer", prefer)
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val error = runCatching { JSONObject(response) }.getOrNull()
                    val message = error?.optString("message").orEmpty()
                        .ifBlank { error?.optString("msg").orEmpty() }
                        .ifBlank { error?.optString("hint").orEmpty() }
                    val errorCode = error?.optString("code").orEmpty()
                    val details = error?.optString("details").orEmpty()
                    val diagnostic = buildString {
                        append("Cloud sync request failed: ")
                        append(method)
                        append(" ")
                        append(path.substringBefore("?"))
                        append(" returned HTTP ")
                        append(code)
                        if (errorCode.isNotBlank()) append(" [").append(errorCode).append("]")
                        if (message.isNotBlank()) append(": ").append(message)
                        if (details.isNotBlank() && details != "null") append(" (").append(details).append(")")
                    }
                    throw IllegalStateException(
                        if (code == 401) "$diagnostic. Sign in again to refresh your session."
                        else diagnostic
                    )
                }
                return response
            } finally {
                connection.disconnect()
            }
        }

        val user = JSONObject(request("auth/v1/user"))
        val accountId = user.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Your account session is no longer valid. Sign in again.")

        fun profileJson(p: NexvioProfile) = JSONObject()
            .put("id", p.id).put("name", p.name).put("avatar", p.avatarId)
            .put("customAvatar", p.customAvatarUrl).put("primary", p.isPrimary)
            .put("primaryAddons", p.usePrimaryAddons).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)

        // Local Android preferences are device-wide, not account-owned. Never merge them
        // into a different Supabase account. When the authenticated user changes, discard
        // only the device cache; each account's cloud rows remain untouched and are restored
        // below from rows filtered by the authenticated accountId.
        val ownerPrefs = context.getSharedPreferences("nexvio_local_account_owner", Context.MODE_PRIVATE)
        val previousOwner = ownerPrefs.getString("account_id", null)
        if (previousOwner != accountId) {
            context.getSharedPreferences("nexvio_profiles", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("nexvio_watch_history", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("nexvio_watchlist", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("nexvio_addons", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("nexvio_plugin_repositories", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("nexvio_iptv", Context.MODE_PRIVATE).edit().clear().apply()
            ownerPrefs.edit().putString("account_id", accountId).apply()
        }

        val localProfiles = NexvioProfileStore.all(context)
        val remoteRows = JSONArray(request("rest/v1/nexvio_profiles?select=profile_id,profile_data&account_id=eq.$accountId"))
        val tombstonePrefs = context.getSharedPreferences("nexvio_profile_tombstones", Context.MODE_PRIVATE)
        val tombstoneKey = nexvioProfileTombstoneKey(context)
        val deletedProfileIds = tombstonePrefs.getStringSet(tombstoneKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        val remoteDeletedIds = mutableSetOf<String>()
        for (i in 0 until remoteRows.length()) {
            val data = remoteRows.optJSONObject(i)?.optJSONObject("profile_data") ?: continue
            if (data.optBoolean("deleted", false)) {
                data.optString("id").takeIf { it.isNotBlank() }?.let(remoteDeletedIds::add)
            }
        }
        deletedProfileIds.addAll(remoteDeletedIds)
        tombstonePrefs.edit().putStringSet(tombstoneKey, deletedProfileIds).apply()

        // Tombstones are durable rows in the existing account-owned profile table. A stale
        // device can never recreate a deleted profile because the tombstone wins on every sync.
        val tombstonePayload = JSONArray()
        deletedProfileIds.filterNot { it in remoteDeletedIds }.forEach { deletedId ->
            val tombstone = JSONObject()
                .put("id", deletedId)
                .put("deleted", true)
                .put("deletedAt", System.currentTimeMillis())
            tombstonePayload.put(JSONObject()
                .put("account_id", accountId)
                .put("profile_id", deletedId)
                .put("profile_data", tombstone)
                .put("updated_at", java.time.Instant.now().toString()))
        }
        if (tombstonePayload.length() > 0) {
            request("rest/v1/nexvio_profiles?on_conflict=account_id,profile_id", "POST", tombstonePayload.toString(),
                "resolution=merge-duplicates,return=minimal")
        }

        val merged = linkedMapOf<String, NexvioProfile>()
        for (i in 0 until remoteRows.length()) {
            val data = remoteRows.optJSONObject(i)?.optJSONObject("profile_data") ?: continue
            val id = data.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (data.optBoolean("deleted", false) || id in deletedProfileIds) continue
            merged[id] = NexvioProfile(
                id = id,
                name = data.optString("name", "Main Profile"),
                avatarId = data.optString("avatar", "fox"),
                customAvatarUrl = data.optString("customAvatar", ""),
                isPrimary = data.optBoolean("primary", id == "primary"),
                usePrimaryAddons = data.optBoolean("primaryAddons", true),
                createdAt = data.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = data.optLong("updatedAt", System.currentTimeMillis())
            )
        }
        // Resolve profile conflicts by update time. A freshly initialized default profile
        // must not replace an existing cloud primary profile just because it was created later.
        localProfiles.forEach { local ->
            if (local.id in deletedProfileIds) return@forEach
            val cloud = merged[local.id]
            if (cloud == null) {
                merged[local.id] = local
            } else {
                val localIsUntouchedDefault = local.id == "primary" &&
                    local.name == "Main Profile" && local.avatarId == "fox" &&
                    local.customAvatarUrl.isBlank() && (local.updatedAt - local.createdAt) < 1000L
                if (!localIsUntouchedDefault && local.updatedAt >= cloud.updatedAt) {
                    merged[local.id] = local
                }
            }
        }
        // A fresh account with no profiles must stay empty until the user creates one.
        // Never manufacture a default profile during background cloud sync.
        if (merged.isEmpty()) {
            return@withContext Result(0, 0, "No profiles yet. Create your first profile to enable cloud sync.")
        }
        NexvioProfileStore.save(context, merged.values.toList())

        val profilePayload = JSONArray()
        var profilesChanged = false
        merged.values.forEach { p ->
            val profileData = profileJson(p)
            val remoteData = (0 until remoteRows.length()).asSequence()
                .mapNotNull { remoteRows.optJSONObject(it) }
                .firstOrNull { it.optString("profile_id") == p.id }
                ?.optJSONObject("profile_data")
            if (remoteData == null || canonicalJson(profileData) != canonicalJson(remoteData)) {
                profilesChanged = true
            }
            profilePayload.put(JSONObject()
                .put("account_id", accountId)
                .put("profile_id", p.id)
                .put("profile_data", profileData)
                .put("updated_at", java.time.Instant.now().toString()))
        }
        if (profilePayload.length() > 0 && profilesChanged) {
            request("rest/v1/nexvio_profiles?on_conflict=account_id,profile_id", "POST", profilePayload.toString(),
                "resolution=merge-duplicates,return=minimal")
        }

        var syncedSettings = 0
        merged.values.forEach { profile ->
            val prefsName = if (profile.isPrimary) "nexvio_settings" else "nexvio_settings_profile_${profile.id}"
            syncPreferenceBucket(
                context, accountId, profile.id, "settings", prefsName, ::request,
            )
            syncedSettings++
        }
        // Sync profile-owned viewing data, including watchlists and playback history.
        merged.values.forEach { profile ->
            val suffix = if (profile.id == "primary") "" else "_${profile.id}"
            syncPreferenceBucket(
                context, accountId, profile.id, "watchlist", "nexvio_watchlist${suffix}", ::request,
            )
            syncPreferenceBucket(
                context, accountId, profile.id, "watch_history", "nexvio_watch_history${suffix}", ::request,
            )
        }

        // Installed add-ons, plugin repositories, and IPTV URL/favorites are shared app-wide.
        // A local SAF document URI is device-specific, so never upload or restore it.
        // Use an existing account-owned profile ID to satisfy the profile_state foreign key.
        val sharedStateProfileId = merged.values.firstOrNull { it.isPrimary }?.id
            ?: merged.values.first().id
        syncPreferenceBucket(context, accountId, sharedStateProfileId, "shared_addons", "nexvio_addons", ::request)
        syncPreferenceBucket(context, accountId, sharedStateProfileId, "shared_plugin_repositories", "nexvio_plugin_repositories", ::request)
        syncPreferenceBucket(context, accountId, sharedStateProfileId, "shared_iptv", "nexvio_iptv", ::request,
            skipDeviceLocalIptv = true)

        // Seed the built-in IPTV playlist once per signed-in account, after restoring
        // that account's cloud state. The marker prevents a user's later Clear action
        // from causing the default source to be re-added on every sync.
        val iptvPrefs = context.getSharedPreferences("nexvio_iptv", Context.MODE_PRIVATE)
        if (iptvPrefs.getString("source", null).isNullOrBlank()) {
            iptvPrefs.edit()
                .putString("source", "http://108.175.13.7/playlist.m3u")
                .putString("label", "Nexvio IPTV")
                .putBoolean("local", false)
                .putBoolean("default_playlist_initialized", true)
                .commit()
        }

        Result(merged.size, syncedSettings, "Cloud sync complete: profiles, settings, add-ons, plugins, IPTV URL/favorites, watchlists, and watch history synced.")
    }

    /**
     * Synchronize a complete state snapshot with a per-device baseline.
     *
     * The old implementation union-merged arrays. That makes deletions impossible: a movie
     * removed on one device is reintroduced from the other device's stale list. A baseline lets
     * us detect which side changed since its last successful sync. If only local changed, upload
     * local; if only cloud changed, restore cloud. If both changed between syncs, the device
     * explicitly syncing now wins (last successful sync wins the conflict).
     */
    private fun syncPreferenceBucket(
        context: Context,
        accountId: String,
        profileId: String,
        stateKey: String,
        prefsName: String,
        request: (String, String, String?, String?) -> String,
        skipDeviceLocalIptv: Boolean = false,
    ) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val baselinePrefs = context.getSharedPreferences("nexvio_sync_baselines", Context.MODE_PRIVATE)
        val baselineKey = "${accountId}_${profileId}_${stateKey}"
        val remoteRows = JSONArray(request(
            "rest/v1/nexvio_profile_state?select=state_data,updated_at&account_id=eq.$accountId&profile_id=eq.$profileId&state_key=eq.$stateKey",
            "GET", null, null
        ))
        val remote = remoteRows.optJSONObject(0)?.optJSONObject("state_data") ?: JSONObject()
        val baselineText = baselinePrefs.getString(baselineKey, null)
        val baseline = baselineText?.let { runCatching { JSONObject(it) }.getOrNull() }

        fun localSnapshot(): JSONObject {
            val out = JSONObject()
            prefs.all.toSortedMap().forEach { (key, value) ->
                if (skipDeviceLocalIptv && key in setOf("source", "label", "local") &&
                    (prefs.getBoolean("local", false) || remote.optBoolean("local", false))) return@forEach
                putPreference(out, key, value)
            }
            return out
        }

        val local = localSnapshot()
        val localChanged = baseline != null && canonicalJson(local) != canonicalJson(baseline)
        val remoteChanged = baseline != null && canonicalJson(remote) != canonicalJson(baseline)
        val remoteHasData = remote.length() > 0
        val localHasData = local.length() > 0

        val useRemote = when {
            // Without a baseline this device cannot distinguish app defaults from user edits.
            // Prefer existing cloud state to prevent a fresh/stale local snapshot overwriting it.
            // If cloud has no data, the local snapshot is uploaded below.
            baseline == null -> remoteHasData
            remoteChanged && !localChanged -> true
            localChanged && !remoteChanged -> false
            remoteChanged && localChanged -> false // explicit sync on this device wins a conflict
            else -> !localHasData && remoteHasData
        }
        val chosen = if (useRemote) remote else local
        val keepLocalIptvFields = skipDeviceLocalIptv && prefs.getBoolean("local", false)
        val localIptvSource = prefs.all["source"]
        val localIptvLabel = prefs.all["label"]
        val localIptvFlag = prefs.all["local"]

        // Only touch local preferences when the selected snapshot actually differs.
        // Preserve device-specific IPTV document URI fields; they cannot be restored on another phone.
        if (canonicalJson(chosen) != canonicalJson(local)) {
            val editor = prefs.edit().clear()
            if (keepLocalIptvFields) {
                when (localIptvSource) {
                    is String -> editor.putString("source", localIptvSource)
                }
                when (localIptvLabel) {
                    is String -> editor.putString("label", localIptvLabel)
                }
                when (localIptvFlag) {
                    is Boolean -> editor.putBoolean("local", localIptvFlag)
                }
            }
            val keys = chosen.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (skipDeviceLocalIptv && key in setOf("source", "label", "local") &&
                    (prefs.getBoolean("local", false) || remote.optBoolean("local", false))) continue
                val value = chosen.opt(key)
                if (value != JSONObject.NULL) applyBucketValue(editor, key, value)
            }
            editor.commit()
        }

        // Avoid repeated cloud writes for unchanged snapshots. Reads still detect changes made
        // on another device, while this POST is only needed when the chosen state differs.
        if (canonicalJson(chosen) != canonicalJson(remote)) {
            val upload = JSONObject(chosen.toString())
            val row = JSONArray().put(JSONObject()
                .put("account_id", accountId)
                .put("profile_id", profileId)
                .put("state_key", stateKey)
                .put("state_data", upload)
                .put("updated_at", java.time.Instant.now().toString()))
            request(
                "rest/v1/nexvio_profile_state?on_conflict=account_id,profile_id,state_key",
                "POST", row.toString(), "resolution=merge-duplicates,return=minimal"
            )
        }
        baselinePrefs.edit().putString(baselineKey, canonicalJson(chosen)).commit()
    }

    /** Stable key ordering prevents JSON object order from looking like a data change. */
    private fun canonicalJson(value: JSONObject): String {
        val keys = value.keys().asSequence().toList().sorted()
        val out = JSONObject()
        keys.forEach { key ->
            val item = value.opt(key)
            out.put(key, when (item) {
                is JSONObject -> JSONObject(canonicalJson(item))
                is JSONArray -> canonicalArray(item)
                else -> item
            })
        }
        return out.toString()
    }

    private fun canonicalArray(value: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until value.length()) {
            val item = value.opt(i)
            out.put(when (item) {
                is JSONObject -> JSONObject(canonicalJson(item))
                is JSONArray -> canonicalArray(item)
                else -> item
            })
        }
        return out
    }

    private fun isEmptyBucketValue(value: Any): Boolean = when (value) {
        is String -> value.isBlank() || value == "[]" || value == "{}"
        is Set<*> -> value.isEmpty()
        else -> false
    }

    private fun isJsonArrayString(value: Any): Boolean =
        value is String && value.trim().startsWith("[") && runCatching { JSONArray(value) }.isSuccess

    private fun mergeBucketValue(key: String, local: Any, remote: Any?): Any {
        if (remote == null || remote == JSONObject.NULL) return local

        // Supabase returns JSON arrays as JSONArray objects, while Android stores
        // these app-owned lists as JSON strings. Normalize both representations.
        val localArray = (local as? String)
            ?.takeIf { isJsonArrayString(it) }
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
        val remoteArray = when (remote) {
            is JSONArray -> remote
            is String -> if (isJsonArrayString(remote)) runCatching { JSONArray(remote) }.getOrNull() else null
            else -> null
        }
        if (localArray != null && remoteArray != null) {
            val byId = linkedMapOf<String, Any?>()
            fun identity(item: Any?, index: Int): String {
                if (item is JSONObject) {
                    val id = item.optString("id")
                    if (id.isNotBlank() && id != "null") {
                        if (key == "items") return "${item.optString("mediaType")}:$id"
                        return id
                    }
                    val url = item.optString("manifestUrl")
                    if (url.isNotBlank() && url != "null") return url
                    val urlValue = item.optString("url")
                    if (urlValue.isNotBlank() && urlValue != "null") return urlValue
                }
                return item?.toString() ?: "null:$index"
            }
            for (i in 0 until remoteArray.length()) {
                val item = remoteArray.opt(i)
                byId[identity(item, i)] = item
            }
            for (i in 0 until localArray.length()) {
                val item = localArray.opt(i)
                val id = identity(item, i)
                val existing = byId[id]
                if (item is JSONObject && existing is JSONObject) {
                    val localUpdated = item.optLong("updated", item.optLong("lastCheckedAt", 0L))
                    val remoteUpdated = existing.optLong("updated", existing.optLong("lastCheckedAt", 0L))
                    if (localUpdated >= remoteUpdated) byId[id] = item
                } else {
                    byId[id] = item
                }
            }
            return JSONArray(byId.values).toString()
        }

        if (local is Set<*> && remote is JSONArray) {
            return (local.map { it.toString() } + (0 until remote.length()).map { remote.optString(it) }).toSet()
        }
        return if (isEmptyBucketValue(local)) remote else local
    }

    private fun applyBucketValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> Unit
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Double -> editor.putFloat(key, value.toFloat())
            is JSONArray -> if (key == "favorites") editor.putStringSet(key, (0 until value.length()).map { value.optString(it) }.toSet()) else editor.putString(key, value.toString())
            is JSONObject -> editor.putString(key, value.toString())
            is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            else -> editor.putString(key, value.toString())
        }
    }

    private fun putPreference(json: JSONObject, key: String, value: Any?) {
        when (value) {
            null -> json.put(key, JSONObject.NULL)
            is String, is Boolean, is Int, is Long, is Float, is Double -> json.put(key, value)
            is Set<*> -> json.put(key, JSONArray(value.map { it.toString() }.sorted()))
            is JSONArray, is JSONObject -> json.put(key, value)
            else -> json.put(key, value.toString())
        }
    }

    private fun settingsToMap(json: JSONObject): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.opt(key)) {
                is String -> result[key] = value
                is Boolean -> result[key] = value
                is Int -> result[key] = value
                is Long -> result[key] = value
                is Double -> result[key] = value
                is JSONArray -> result[key] = (0 until value.length()).map { value.optString(it) }.toSet()
            }
        }
        return result
    }

    private fun applyValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Double -> editor.putFloat(key, value.toFloat())
            is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
        }
    }
}
