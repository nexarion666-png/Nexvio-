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
                    val message = error?.optString("message").orEmpty().ifBlank { error?.optString("hint").orEmpty() }
                    throw IllegalStateException(message.ifBlank { "Cloud sync failed (HTTP $code). Sign in again if your session expired." })
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

        val localProfiles = NexvioProfileStore.all(context)
        val remoteRows = JSONArray(request("rest/v1/nexvio_profiles?select=profile_id,profile_data&account_id=eq.$accountId"))
        val merged = linkedMapOf<String, NexvioProfile>()
        for (i in 0 until remoteRows.length()) {
            val data = remoteRows.optJSONObject(i)?.optJSONObject("profile_data") ?: continue
            val id = data.optString("id").takeIf { it.isNotBlank() } ?: continue
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
        // Keep locally created/edited profiles when IDs overlap; include cloud-only profiles.
        localProfiles.forEach { merged[it.id] = it }
        if (merged.isEmpty()) merged["primary"] = NexvioProfile("primary", "Main Profile", "fox", isPrimary = true)
        NexvioProfileStore.save(context, merged.values.toList())

        val profilePayload = JSONArray()
        merged.values.forEach { p ->
            profilePayload.put(JSONObject()
                .put("account_id", accountId)
                .put("profile_id", p.id)
                .put("profile_data", profileJson(p))
                .put("updated_at", java.time.Instant.now().toString()))
        }
        if (profilePayload.length() > 0) {
            request("rest/v1/nexvio_profiles?on_conflict=account_id,profile_id", "POST", profilePayload.toString(),
                "resolution=merge-duplicates,return=minimal")
        }

        var syncedSettings = 0
        merged.values.forEach { profile ->
            val prefsName = if (profile.isPrimary) "nexvio_settings" else "nexvio_settings_profile_${profile.id}"
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val remoteState = JSONArray(request(
                "rest/v1/nexvio_profile_state?select=state_data&account_id=eq.$accountId&profile_id=eq.${profile.id}&state_key=eq.settings"
            ))
            val settings = JSONObject()
            if (remoteState.length() > 0) {
                val remoteSettings = remoteState.optJSONObject(0)?.optJSONObject("state_data")
                if (remoteSettings != null) {
                    val keys = remoteSettings.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (!prefs.contains(key)) putPreference(settings, key, remoteSettings.opt(key))
                    }
                    // Keep locally set values when present; fill missing settings from cloud.
                    val restored = settingsToMap(remoteSettings)
                    val editor = prefs.edit()
                    restored.forEach { (key, value) -> if (!prefs.contains(key)) applyValue(editor, key, value) }
                    editor.apply()
                }
            }
            val all = prefs.all
            all.forEach { (key, value) -> putPreference(settings, key, value) }
            val row = JSONArray().put(JSONObject()
                .put("account_id", accountId)
                .put("profile_id", profile.id)
                .put("state_key", "settings")
                .put("state_data", settings)
                .put("updated_at", java.time.Instant.now().toString()))
            request("rest/v1/nexvio_profile_state?on_conflict=account_id,profile_id,state_key", "POST", row.toString(),
                "resolution=merge-duplicates,return=minimal")
            syncedSettings++
        }
        // Sync profile-owned viewing data, including watchlists and playback history.
        merged.values.forEach { profile ->
            val suffix = if (profile.id == "primary") "" else "_${profile.id}"
            syncPreferenceBucket(
                context, accountId, profile.id, "watchlist", "nexvio_watchlist${suffix}", ::request
            )
            syncPreferenceBucket(
                context, accountId, profile.id, "watch_history", "nexvio_watch_history${suffix}", ::request
            )
        }

        // Installed add-ons, plugin repositories, and IPTV URL/favorites are shared app-wide.
        // A local SAF document URI is device-specific, so never upload or restore it.
        syncPreferenceBucket(context, accountId, "primary", "shared_addons", "nexvio_addons", ::request)
        syncPreferenceBucket(context, accountId, "primary", "shared_plugin_repositories", "nexvio_plugin_repositories", ::request)
        syncPreferenceBucket(context, accountId, "primary", "shared_iptv", "nexvio_iptv", ::request, skipDeviceLocalIptv = true)

        Result(merged.size, syncedSettings, "Cloud sync complete: profiles, settings, add-ons, plugins, IPTV URL/favorites, watchlists, and watch history synced.")
    }

    /**
     * Sync a named SharedPreferences store through the existing account/profile RLS table.
     * List-valued JSON strings are merged by stable item identity so one device does not
     * erase entries added on another device. Authentication/session preferences are never passed here.
     */
    private fun syncPreferenceBucket(
        context: Context,
        accountId: String,
        profileId: String,
        stateKey: String,
        prefsName: String,
        request: (String, String, String?, String?) -> String,
        skipDeviceLocalIptv: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val remoteRows = JSONArray(request(
            "rest/v1/nexvio_profile_state?select=state_data&account_id=eq.$accountId&profile_id=eq.$profileId&state_key=eq.$stateKey",
            "GET", null, null
        ))
        val remote = remoteRows.optJSONObject(0)?.optJSONObject("state_data") ?: JSONObject()
        val localIsDocumentPlaylist = skipDeviceLocalIptv && prefs.getBoolean("local", false)
        val merged = JSONObject()
        val remoteKeys = remote.keys()
        while (remoteKeys.hasNext()) {
            val key = remoteKeys.next()
            if (skipDeviceLocalIptv && key in setOf("source", "label", "local") &&
                (localIsDocumentPlaylist || remote.optBoolean("local", false))) continue
            val remoteValue = remote.opt(key)
            val localValue = prefs.all[key]
            if (localValue == null) {
                putPreference(merged, key, remoteValue)
            } else {
                putPreference(merged, key, mergeBucketValue(key, localValue, remoteValue))
            }
        }
        prefs.all.forEach { (key, value) ->
            if (skipDeviceLocalIptv && localIsDocumentPlaylist && key in setOf("source", "label", "local")) return@forEach
            if (!merged.has(key)) putPreference(merged, key, value)
        }

        // Restore remote values that were absent locally; merge JSON arrays with local data.
        val editor = prefs.edit()
        val restoreKeys = merged.keys()
        while (restoreKeys.hasNext()) {
            val key = restoreKeys.next()
            if (skipDeviceLocalIptv && key in setOf("source", "label", "local") &&
                (localIsDocumentPlaylist || remote.optBoolean("local", false))) continue
            val value = merged.opt(key)
            if (value == JSONObject.NULL) continue
            val localValue = prefs.all[key]
            if (localValue == null || isEmptyBucketValue(localValue) || isJsonArrayString(localValue)) {
                applyBucketValue(editor, key, value)
            }
        }
        editor.apply()

        val upload = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (skipDeviceLocalIptv && prefs.getBoolean("local", false) && key in setOf("source", "label", "local")) return@forEach
            putPreference(upload, key, value)
        }
        // Preserve cloud values merged above even when local preferences were empty.
        val mergedKeys = merged.keys()
        while (mergedKeys.hasNext()) {
            val key = mergedKeys.next()
            if (!upload.has(key)) upload.put(key, merged.opt(key))
        }
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

    private fun isEmptyBucketValue(value: Any): Boolean = when (value) {
        is String -> value.isBlank() || value == "[]" || value == "{}"
        is Set<*> -> value.isEmpty()
        else -> false
    }

    private fun isJsonArrayString(value: Any): Boolean =
        value is String && value.trim().startsWith("[") && runCatching { JSONArray(value) }.isSuccess

    private fun mergeBucketValue(key: String, local: Any, remote: Any?): Any {
        if (remote == null || remote == JSONObject.NULL) return local
        if (local is String && remote is String && isJsonArrayString(local) && isJsonArrayString(remote)) {
            val localArray = JSONArray(local)
            val remoteArray = JSONArray(remote)
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
                } else byId[id] = item
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
            is Set<*> -> json.put(key, JSONArray(value.map { it.toString() }))
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
