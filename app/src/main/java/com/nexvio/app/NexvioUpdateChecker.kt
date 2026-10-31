package com.nexvio.app

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LATEST_UPDATE_JSON = "https://nexvio-updates-site.vercel.app/latest.json"

private data class NexvioAvailableUpdate(
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val versionCode: Int
)

@Composable
fun NexvioUpdateChecker() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var checkTrigger by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<NexvioAvailableUpdate?>(null) }
    var dismissedVersion by remember { mutableStateOf<String?>(null) }

    // Check again whenever the user returns to Nexvio, not just on the first launch.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(checkTrigger) {
        val latest = withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(LATEST_UPDATE_JSON).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Nexvio-Android")
                }
                if (connection.responseCode !in 200..299) return@withContext null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val version = json.optString("version").removePrefix("v").trim()
                val url = json.optString("downloadUrl").trim()
                val remoteCode = json.optInt("versionCode", 0)
                val hasNewerVersion = if (version.isNotBlank()) {
                    isNewerVersion(version, BuildConfig.VERSION_NAME)
                } else {
                    remoteCode > 0 && remoteCode > BuildConfig.VERSION_CODE
                }
                if (version.isNotBlank() && url.startsWith("https://") && hasNewerVersion) {
                    NexvioAvailableUpdate(
                        version = version,
                        releaseNotes = json.optString("releaseNotes"),
                        downloadUrl = url,
                        versionCode = remoteCode
                    )
                } else null
            } catch (_: Exception) {
                null // An offline or unavailable update server must never block app startup.
            } finally {
                connection?.disconnect()
            }
        }
        update = latest
    }

    val available = update
    if (available != null && dismissedVersion != available.version) {
        AlertDialog(
            onDismissRequest = { dismissedVersion = available.version },
            title = { Text("Nexvio update available") },
            text = {
                Text(
                    "Version ${available.version} is ready. You have version ${BuildConfig.VERSION_NAME}.\n\n" +
                        available.releaseNotes.ifBlank { "Bug fixes and improvements." }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.downloadUrl)))
                    }
                    dismissedVersion = available.version
                }) { Text("Download update") }
            },
            dismissButton = {
                TextButton(onClick = { dismissedVersion = available.version }) { Text("Later") }
            }
        )
    }
}

private fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(value: String) = value.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av > bv
    }
    return false
}
