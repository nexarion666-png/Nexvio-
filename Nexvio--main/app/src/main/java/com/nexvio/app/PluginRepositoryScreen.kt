package com.nexvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PluginRepositoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PluginRepositoryStore(context) }
    val scope = rememberCoroutineScope()
    var repositories by remember { mutableStateOf(store.all()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var manifestUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() { repositories = store.all() }

    Column(Modifier.fillMaxSize().background(AppAppearanceState.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹  Back", color = AppAppearanceState.accent, modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.weight(1f))
            Text("Plugin repositories", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Add an HTTPS Nuvio-compatible manifest. Enabled providers are tried when you use Find streams; providers requiring unsupported Node modules may not work.",
            color = AppAppearanceState.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        Button(
            onClick = { manifestUrl = ""; message = null; showAddDialog = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
        ) { Text("＋  Add repository") }

        message?.let { Text(it, color = AppAppearanceState.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }

        if (busy) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Text("  Fetching and validating manifest…", modifier = Modifier.padding(start = 10.dp))
            }
        }

        if (repositories.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No repositories added", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
                Text("Tap “Add repository” and paste the HTTPS URL of a compatible JSON manifest.", color = AppAppearanceState.muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(repositories, key = { it.manifestUrl }) { repository ->
                    Surface(color = AppAppearanceState.panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(repository.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (repository.version.isNotBlank()) Text("Version ${repository.version}", color = AppAppearanceState.muted, fontSize = 12.sp)
                            if (repository.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(repository.description, color = AppAppearanceState.muted, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(repository.manifestUrl, color = AppAppearanceState.accent, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("${repository.providers.size} provider(s) listed", color = Color.White, fontSize = 12.sp)
                            repository.providers.forEach { provider ->
                                Text("• ${provider.name}", color = AppAppearanceState.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val success = store.setRepositoryEnabled(repository.manifestUrl, !repository.enabled)
                                    refresh()
                                    message = if (success) "Repository ${if (repository.enabled) "disabled" else "enabled"}." else "Could not update repository."
                                }) { Text(if (repository.enabled) "Disable" else "Enable") }
                                TextButton(onClick = {
                                    val success = store.remove(repository.manifestUrl)
                                    refresh()
                                    message = if (success) "Repository removed." else "Could not remove repository."
                                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showAddDialog = false },
            title = { Text("Add plugin repository") },
            text = {
                Column {
                    Text("Paste the HTTPS URL to a JSON manifest. The manifest must include a name and a providers or scrapers array with provider names and file URLs.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manifestUrl,
                        onValueChange = { manifestUrl = it },
                        label = { Text("Manifest URL") },
                        placeholder = { Text("https://example.com/manifest.json") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                Button(enabled = !busy && manifestUrl.isNotBlank(), onClick = {
                    val safeUrl = PluginUrlPolicy.normalizeManifestUrl(manifestUrl)
                    if (safeUrl == null) {
                        message = "Enter a valid HTTPS URL without credentials or a fragment."
                    } else {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                val json = withContext(Dispatchers.IO) { PluginManifestFetcher.fetch(safeUrl) }
                                val parsed = PluginManifestParser.parse(json, safeUrl)
                                val repository = PluginRepository(
                                    id = safeUrl,
                                    manifestUrl = safeUrl,
                                    name = parsed.name,
                                    description = parsed.description,
                                    version = parsed.version,
                                    providers = parsed.providers
                                )
                                val result = withContext(Dispatchers.IO) { store.save(repository, json) }
                                result.getOrThrow()
                                refresh()
                                showAddDialog = false
                                manifestUrl = ""
                                message = "Repository added successfully. Enabled compatible providers will be tried when you use Find streams."
                            } catch (error: Exception) {
                                message = error.message?.takeIf { it.isNotBlank() } ?: "Could not add this repository."
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text(if (busy) "Checking…" else "Add") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}
