package com.nexvio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Metadata for a remote JavaScript provider repository.
 *
 * This layer deliberately stores metadata only. Downloaded provider code is not evaluated
 * by PR #31; execution belongs in a separately reviewed runtime integration.
 */
data class PluginRepository(
    val id: String,
    val manifestUrl: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val providers: List<PluginProvider> = emptyList(),
    val enabled: Boolean = true,
    val lastCheckedAt: Long? = null
)

data class PluginProvider(
    val id: String,
    val name: String,
    val description: String = "",
    val fileUrl: String,
    val enabled: Boolean = true,
    val hasSettings: Boolean = false
)

data class PluginManifest(
    val name: String,
    val description: String = "",
    val version: String = "",
    val providers: List<PluginProvider>
)

object PluginUrlPolicy {
    fun normalizeManifestUrl(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) return null
        return try {
            val uri = URI(value).normalize()
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" || uri.host.isNullOrBlank() ||
                uri.userInfo != null || uri.fragment != null) return null
            uri.toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    fun resolveProviderUrl(manifestUrl: String, file: String): String? {
        val safeManifest = normalizeManifestUrl(manifestUrl) ?: return null
        val candidate = file.trim()
        if (candidate.isEmpty() || candidate.length > MAX_URL_LENGTH) return null
        return try {
            val resolved = URI(safeManifest).resolve(URI(candidate)).normalize()
            normalizeManifestUrl(resolved.toASCIIString())
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_URL_LENGTH = 2048
}

object PluginManifestParser {
    fun parse(json: String, manifestUrl: String): PluginManifest {
        require(json.length <= MAX_MANIFEST_CHARS) { "Plugin manifest exceeds the size limit." }
        val root = JSONObject(json)
        val name = root.optString("name").trim()
        require(name.isNotEmpty()) { "Plugin manifest is missing a name." }

        val providersJson = root.optJSONArray("providers")
            ?: root.optJSONArray("scrapers")
            ?: JSONArray()
        require(providersJson.length() <= MAX_PROVIDERS) { "Plugin manifest contains too many providers." }

        val providers = buildList {
            for (index in 0 until providersJson.length()) {
                val item = providersJson.optJSONObject(index) ?: continue
                val providerName = item.optString("name").trim()
                val file = item.optString("file").ifBlank { item.optString("filename") }.trim()
                if (providerName.isEmpty() || file.isEmpty()) continue
                val fileUrl = PluginUrlPolicy.resolveProviderUrl(manifestUrl, file) ?: continue
                val id = item.optString("id").trim().ifBlank { fileUrl }
                add(PluginProvider(
                    id = id,
                    name = providerName,
                    description = item.optString("description").trim(),
                    fileUrl = fileUrl,
                    hasSettings = item.optBoolean("hasSettings", item.optJSONObject("settings") != null)
                ))
            }
        }
        require(providers.isNotEmpty()) { "Plugin manifest has no valid providers." }
        return PluginManifest(
            name = name,
            description = root.optString("description").trim(),
            version = root.optString("version").trim(),
            providers = providers.distinctBy { it.id }
        )
    }

    private const val MAX_MANIFEST_CHARS = 1_000_000
    private const val MAX_PROVIDERS = 200
}

/**
 * Local repository persistence. Only repository metadata is persisted; provider source code
 * is not downloaded or executed by this class.
 */
class PluginRepositoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PluginRepository> = runCatching {
        val array = JSONArray(prefs.getString(KEY_REPOSITORIES, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = PluginUrlPolicy.normalizeManifestUrl(item.optString("manifestUrl")) ?: continue
                val manifest = runCatching {
                    PluginManifestParser.parse(item.optString("manifestJson"), url)
                }.getOrNull() ?: continue
                val enabledIds = item.optJSONArray("disabledProviderIds")?.let { ids ->
                    buildSet { for (j in 0 until ids.length()) add(ids.optString(j)) }
                }.orEmpty()
                val providers = manifest.providers.map { it.copy(enabled = it.id !in enabledIds) }
                add(PluginRepository(
                    id = url,
                    manifestUrl = url,
                    name = manifest.name,
                    description = manifest.description,
                    version = manifest.version,
                    providers = providers,
                    enabled = item.optBoolean("enabled", true),
                    lastCheckedAt = item.optLong("lastCheckedAt").takeIf { it > 0L }
                ))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(repository: PluginRepository, manifestJson: String): Result<Unit> = runCatching {
        val url = PluginUrlPolicy.normalizeManifestUrl(repository.manifestUrl)
            ?: error("Use a valid HTTPS manifest URL.")
        val manifest = PluginManifestParser.parse(manifestJson, url)
        val root = JSONObject()
        val existing = JSONArray(prefs.getString(KEY_REPOSITORIES, "[]"))
        val output = JSONArray()
        var replaced = false
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            if (PluginUrlPolicy.normalizeManifestUrl(item.optString("manifestUrl")) == url) {
                output.put(record(root, url, repository.enabled, manifestJson, repository.providers))
                replaced = true
            } else output.put(item)
        }
        if (!replaced) output.put(record(root, url, repository.enabled, manifestJson, repository.providers))
        check(prefs.edit().putString(KEY_REPOSITORIES, output.toString()).commit()) {
            "Could not save plugin repository."
        }
    }

    @Synchronized
    fun setRepositoryEnabled(manifestUrl: String, enabled: Boolean): Boolean =
        updateRecord(manifestUrl) { it.put("enabled", enabled) }

    @Synchronized
    fun remove(manifestUrl: String): Boolean {
        val url = PluginUrlPolicy.normalizeManifestUrl(manifestUrl) ?: return false
        val existing = runCatching { JSONArray(prefs.getString(KEY_REPOSITORIES, "[]")) }.getOrDefault(JSONArray())
        val output = JSONArray()
        var removed = false
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            if (PluginUrlPolicy.normalizeManifestUrl(item.optString("manifestUrl")) == url) removed = true
            else output.put(item)
        }
        return removed && prefs.edit().putString(KEY_REPOSITORIES, output.toString()).commit()
    }

    @Synchronized
    fun setProviderEnabled(manifestUrl: String, providerId: String, enabled: Boolean): Boolean =
        updateRecord(manifestUrl) { record ->
            val manifest = runCatching {
                PluginManifestParser.parse(record.optString("manifestJson"), manifestUrl)
            }.getOrNull() ?: return@updateRecord
            val disabled = record.optJSONArray("disabledProviderIds")?.let { ids ->
                buildSet { for (i in 0 until ids.length()) add(ids.optString(i)) }.toMutableSet()
            } ?: mutableSetOf()
            if (manifest.providers.none { it.id == providerId }) return@updateRecord
            if (enabled) disabled.remove(providerId) else disabled.add(providerId)
            record.put("disabledProviderIds", JSONArray(disabled))
        }

    private fun updateRecord(manifestUrl: String, update: (JSONObject) -> Unit): Boolean {
        val url = PluginUrlPolicy.normalizeManifestUrl(manifestUrl) ?: return false
        val existing = runCatching { JSONArray(prefs.getString(KEY_REPOSITORIES, "[]")) }.getOrDefault(JSONArray())
        val output = JSONArray()
        var found = false
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            if (PluginUrlPolicy.normalizeManifestUrl(item.optString("manifestUrl")) == url) {
                update(item)
                found = true
            }
            output.put(item)
        }
        return found && prefs.edit().putString(KEY_REPOSITORIES, output.toString()).commit()
    }

    private fun record(
        target: JSONObject,
        url: String,
        enabled: Boolean,
        manifestJson: String,
        providers: List<PluginProvider>
    ): JSONObject {
        val disabled = providers.filterNot { it.enabled }.map { it.id }
        return target.put("manifestUrl", url)
            .put("enabled", enabled)
            .put("manifestJson", manifestJson)
            .put("disabledProviderIds", JSONArray(disabled))
            .put("lastCheckedAt", System.currentTimeMillis())
    }

    companion object {
        private const val PREFS_NAME = "nexvio_plugin_repositories"
        private const val KEY_REPOSITORIES = "repositories"
    }
}

/** Bounded HTTPS fetch for metadata only; callers should run this on an IO dispatcher. */
object PluginManifestFetcher {
    fun fetch(manifestUrl: String): String {
        val safeUrl = PluginUrlPolicy.normalizeManifestUrl(manifestUrl)
            ?: error("Plugin manifest must use a valid HTTPS URL.")
        val connection = URL(safeUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Nexvio/1.0 plugin-manifest")
            connection.connect()
            require(connection.responseCode in 200..299) { "Manifest request failed (HTTP ${connection.responseCode})." }
            val length = connection.contentLengthLong
            require(length <= MAX_BYTES || length < 0L) { "Plugin manifest exceeds the 1 MB size limit." }
            val output = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                var total = 0
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Plugin manifest exceeds the 1 MB size limit." }
                    output.append(buffer, 0, count)
                }
            }
            return output.toString()
        } finally {
            connection.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 1_000_000
}
