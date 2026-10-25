package com.nexvio.app

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LATEST_UPDATE_JSON = "https://nexvio-updates-site.vercel.app/latest.json"

@Composable
fun NexvioUpdateChecker() {
    val context = LocalContext.current
    var updateVersion by remember { mutableStateOf<String?>(null) }
    var releaseNotes by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val update = withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(LATEST_UPDATE_JSON).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Nexvio-Android")
                }
                if (connection.responseCode !in 200..299) return@withContext null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val version = json.optString("version").removePrefix("v")
                val url = json.optString("downloadUrl")
                if (version.isNotBlank() &&
                    url.startsWith("https://") &&
                    isNewerVersion(version, BuildConfig.VERSION_NAME)
                ) {
                    Triple(version, json.optString("releaseNotes"), url)
                } else null
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
        updateVersion = update?.first
        releaseNotes = update?.second.orEmpty()
        downloadUrl = update?.third
    }

    if (updateVersion != null && downloadUrl != null && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("Nexvio update available") },
            text = {
                Text("Version $updateVersion is available. You have version ${BuildConfig.VERSION_NAME}.\n\n${releaseNotes.ifBlank { "Bug fixes and improvements." }}")
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                    dismissed = true
                }) { Text("Download update") }
            },
            dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Later") } }
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
