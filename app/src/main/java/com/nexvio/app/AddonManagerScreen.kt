package com.nexvio.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private val AddonPanel = Color(0xFF1B1D22)
private val AddonLime = Color(0xFFB7F36B)
private val AddonMuted = Color(0xFF9A9DA6)
private const val ADDON_MAX_BYTES = 1024 * 1024

data class NexvioAddon(
    val id: String,
    val name: String,
    val version: String,
    val manifestUrl: String,
    val description: String,
    val resources: List<String>,
    val types: List<String>,
    val catalogues: List<String>,
    val enabled: Boolean = true,
    val lastError: String? = null
)

private object AddonManifestClient {
    suspend fun fetch(urlText: String): NexvioAddon = withContext(Dispatchers.IO) {
        val uri = try { URI(urlText.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid add-on manifest URL.")
        }
        require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Manifest URL must be HTTP or HTTPS and cannot contain embedded credentials."
        }
        val connection = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Nexvio/1.0 Android")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Manifest server returned HTTP $status.")
            if (connection.contentLengthLong > ADDON_MAX_BYTES) throw IllegalStateException("Manifest exceeds the 1 MB limit.")
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > ADDON_MAX_BYTES) throw IllegalStateException("Manifest exceeds the 1 MB limit.")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) {
                throw IllegalArgumentException("The server did not return a valid JSON manifest.")
            }
            val name = json.optString("name").trim()
            val version = json.optString("version").trim()
            val scrapers = json.optJSONArray("scrapers")
            val isRepositoryManifest = scrapers != null && scrapers.length() > 0
            val declaredId = json.optString("id").trim()
            // Repository-style manifests (including Nuvio's) identify the repository
            // with name/version and list individual providers under "scrapers".
            // Derive a stable local ID only for that recognized format.
            val id = declaredId.ifBlank {
                if (isRepositoryManifest) {
                    "repo-" + name.lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                        .take(48)
                } else ""
            }
            require(name.isNotBlank()) { "Manifest is missing the required name field." }
            require(version.isNotBlank()) { "Manifest is missing the required version field." }
            require(id.isNotBlank()) {
                "Manifest is missing the required id field and has no supported scrapers repository list."
            }
            val scraperEntries = readScrapers(scrapers)
            NexvioAddon(
                id = id, name = name, version = version, manifestUrl = uri.toASCIIString(),
                description = json.optString("description").take(500),
                resources = (readResources(json.optJSONArray("resources")) + scraperEntries).distinct(),
                types = (readStrings(json.optJSONArray("types")) +
                    readScraperTypes(scrapers)).distinct(),
                catalogues = readCatalogues(json.optJSONArray("catalogs"))
            )
        } finally { connection.disconnect() }
    }

    private fun readResources(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                val value = (if (item is JSONObject) item.optString("name") else item as? String).orEmpty().trim()
                if (value.isNotEmpty() && value !in this) add(value)
            }
        }
    }
    private fun readStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList { for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty() && value !in this) add(value)
        } }
    }
    private fun readScrapers(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim().ifBlank { id }
                if (name.isBlank()) continue
                val version = item.optString("version").trim()
                val types = readStrings(item.optJSONArray("supportedTypes"))
                val details = buildList {
                    if (version.isNotBlank()) add("v$version")
                    if (types.isNotEmpty()) add(types.joinToString("/"))
                }.joinToString(", ")
                add(if (details.isBlank()) name else "$name ($details)")
            }
        }
    }

    private fun readScraperTypes(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                addAll(readStrings(item.optJSONArray("supportedTypes")))
            }
        }
    }

    private fun readCatalogues(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList { for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val label = listOf(item.optString("type"), item.optString("id"), item.optString("name"))
                .map { it.trim() }.filter { it.isNotBlank() }.joinToString(" • ")
            if (label.isNotBlank()) add(label)
        } }
    }
}

private class AddonStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexvio_addons", Context.MODE_PRIVATE)
    fun load(): List<NexvioAddon> = try {
        val array = JSONArray(prefs.getString("installed", "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val url = o.optString("manifestUrl")
                if (id.isBlank() || url.isBlank()) continue
                add(NexvioAddon(id, o.optString("name", id), o.optString("version", "unknown"), url,
                    o.optString("description"), strings(o.optJSONArray("resources")), strings(o.optJSONArray("types")),
                    strings(o.optJSONArray("catalogues")), o.optBoolean("enabled", true),
                    o.optString("lastError").takeIf { it.isNotBlank() && it != "null" }))
            }
        }
    } catch (_: Exception) { emptyList() }
    fun save(addons: List<NexvioAddon>) {
        val array = JSONArray()
        addons.forEach { a -> array.put(JSONObject().apply {
            put("id", a.id); put("name", a.name); put("version", a.version); put("manifestUrl", a.manifestUrl)
            put("description", a.description); put("resources", JSONArray(a.resources)); put("types", JSONArray(a.types))
            put("catalogues", JSONArray(a.catalogues)); put("enabled", a.enabled); put("lastError", a.lastError ?: "")
        }) }
        prefs.edit().putString("installed", array.toString()).apply()
    }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
}

@Composable
fun AddonManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AddonStore(context) }
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf(store.load()) }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun save(list: List<NexvioAddon>) { addons = list; store.save(list) }
    fun checkManifest(input: String, testing: Boolean = false) {
        val clean = input.trim()
        if (clean.isBlank()) { message = "Enter a manifest URL first."; failed = true; return }
        scope.launch {
            busy = true; message = null
            try {
                val fetched = AddonManifestClient.fetch(clean)
                val index = addons.indexOfFirst { it.id.equals(fetched.id, true) || it.manifestUrl.equals(fetched.manifestUrl, true) }
                val enabled = addons.getOrNull(index)?.enabled ?: true
                val updated = addons.toMutableList()
                val result = fetched.copy(enabled = enabled, lastError = null)
                if (index >= 0) updated[index] = result else updated.add(result)
                save(updated)
                if (!testing) url = ""
                message = if (testing) "Connection test succeeded: ${result.name}." else "Installed ${result.name}."
                failed = false
            } catch (e: Exception) {
                val reason = e.message?.take(220) ?: "Could not validate this manifest."
                message = reason; failed = true
                val index = addons.indexOfFirst { it.manifestUrl.equals(clean, true) }
                if (index >= 0) save(addons.mapIndexed { i, a -> if (i == index) a.copy(lastError = reason) else a })
            } finally { busy = false }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", color = AddonLime, modifier = Modifier.clickable { onBack() })
                Spacer(Modifier.width(16.dp))
                Column { Text("Add-ons", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Text("Configure compatible content providers", color = AddonMuted, fontSize = 13.sp) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(AddonPanel, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Install an add-on", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Paste the provider's HTTP or HTTPS manifest URL. Install only providers you trust.", color = AddonMuted, fontSize = 12.sp)
                OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Manifest URL") }, placeholder = { Text("https://provider.example/manifest.json") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Button(onClick = { checkManifest(url) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AddonLime, contentColor = Color(0xFF101114))) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF101114)); Spacer(Modifier.width(8.dp)) }
                    Text("Validate and install")
                }
            }
        }
        if (message != null) item {
            Text(message!!, color = if (failed) MaterialTheme.colorScheme.error else AddonLime,
                modifier = Modifier.fillMaxWidth().background(AddonPanel, RoundedCornerShape(12.dp)).padding(12.dp), fontSize = 13.sp)
        }
        item { Text("Installed providers (${addons.size})", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        if (addons.isEmpty()) item {
            Text("No add-ons installed yet. Add a manifest URL above to validate a provider.",
                color = AddonMuted, modifier = Modifier.fillMaxWidth().background(AddonPanel, RoundedCornerShape(14.dp)).padding(16.dp))
        }
        items(addons, key = { it.id }) { addon ->
            Column(Modifier.fillMaxWidth().background(AddonPanel, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(addon.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("v${addon.version} • ${if (addon.enabled) "Enabled" else "Disabled"}", color = AddonMuted, fontSize = 12.sp)
                    }
                    Switch(checked = addon.enabled, onCheckedChange = { enabled ->
                        save(addons.map { if (it.id == addon.id) it.copy(enabled = enabled) else it })
                    })
                }
                if (addon.description.isNotBlank()) Text(addon.description, color = AddonMuted, fontSize = 12.sp)
                Text(addon.manifestUrl, color = AddonMuted, fontSize = 11.sp)
                Text("Resources: ${addon.resources.ifEmpty { listOf("not declared") }.joinToString()}", fontSize = 12.sp)
                Text("Content types: ${addon.types.ifEmpty { listOf("not declared") }.joinToString()}", fontSize = 12.sp)
                if (addon.catalogues.isNotEmpty()) Text("Catalogues: ${addon.catalogues.joinToString("; ")}", color = AddonMuted, fontSize = 11.sp)
                if (addon.lastError != null) Text("Last check failed: ${addon.lastError}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { checkManifest(addon.manifestUrl, true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Test connection") }
                    OutlinedButton(onClick = { save(addons.filterNot { it.id == addon.id }) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Remove") }
                }
            }
        }
        item { Text("Nexvio validates add-on manifests but cannot guarantee provider availability or stream compatibility.", color = AddonMuted, fontSize = 11.sp) }
    }
}
