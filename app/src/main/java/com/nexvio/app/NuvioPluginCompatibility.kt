package com.nexvio.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed metadata for the Nuvio JavaScript-plugin repository format.
 *
 * Nuvio plugin repositories are distinct from Stremio add-on manifests. Their root object
 * contains a "scrapers" array; each entry points to JavaScript through "filename".
 * This parser intentionally does not execute downloaded JavaScript.
 */
data class NuvioPluginRepositoryManifest(
    val name: String,
    val version: String,
    val providers: List<NuvioPluginProvider>
)

data class NuvioPluginProvider(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val fileUrl: String,
    val enabledByDefault: Boolean,
    val hasSettings: Boolean,
    val supportedTypes: List<String>,
    val formats: List<String>,
    val contentLanguages: List<String>,
    val author: String,
    val limited: Boolean,
    val notes: String,
    val disabledPlatforms: List<String>,
    val supportsExternalPlayer: Boolean?
)

object NuvioPluginManifestParser {
    private const val MAX_MANIFEST_CHARS = 1_000_000
    private const val MAX_PROVIDERS = 200
    private const val MAX_LIST_ITEMS = 32

    fun parse(json: String, manifestUrl: String): NuvioPluginRepositoryManifest {
        require(json.length <= MAX_MANIFEST_CHARS) { "Nuvio plugin manifest exceeds the 1 MB limit." }
        val safeManifestUrl = PluginUrlPolicy.normalizeManifestUrl(manifestUrl)
            ?: error("Nuvio plugin manifest must use a valid HTTPS URL.")
        val root = JSONObject(json)
        val name = root.optString("name").trim()
        require(name.isNotEmpty()) { "Nuvio plugin manifest is missing a name." }
        val version = root.optString("version").trim()
        require(version.isNotEmpty()) { "Nuvio plugin manifest is missing a version." }
        val scrapers = root.optJSONArray("scrapers")
            ?: error("This is not a Nuvio plugin repository: expected a scrapers array.")
        require(scrapers.length() in 1..MAX_PROVIDERS) {
            "Nuvio plugin repository must contain between 1 and $MAX_PROVIDERS providers."
        }

        val providers = buildList {
            for (index in 0 until scrapers.length()) {
                val item = scrapers.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val providerName = item.optString("name").trim()
                val filename = item.optString("filename").trim()
                if (id.isEmpty() || providerName.isEmpty() || filename.isEmpty()) continue
                val fileUrl = PluginUrlPolicy.resolveProviderUrl(safeManifestUrl, filename) ?: continue
                add(
                    NuvioPluginProvider(
                        id = id,
                        name = providerName,
                        description = item.optString("description").trim(),
                        version = item.optString("version").trim(),
                        fileUrl = fileUrl,
                        enabledByDefault = item.optBoolean("enabled", true),
                        hasSettings = item.optBoolean("hasSettings", false),
                        supportedTypes = stringList(item.optJSONArray("supportedTypes")),
                        formats = stringList(item.optJSONArray("formats")),
                        contentLanguages = stringList(item.optJSONArray("contentLanguage")),
                        author = item.optString("author").trim(),
                        limited = item.optBoolean("limited", false),
                        notes = item.optString("notes").trim(),
                        disabledPlatforms = stringList(item.optJSONArray("disabledPlatforms")),
                        supportsExternalPlayer = if (item.has("supportsExternalPlayer") && !item.isNull("supportsExternalPlayer")) {
                            item.optBoolean("supportsExternalPlayer")
                        } else null
                    )
                )
            }
        }.distinctBy { it.id }
        require(providers.isNotEmpty()) { "Nuvio plugin repository contains no valid provider entries." }
        return NuvioPluginRepositoryManifest(name, version, providers)
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_LIST_ITEMS)) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }.distinct()
    }
}
