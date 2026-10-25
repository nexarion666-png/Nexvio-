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

private const val LATEST_RELEASE_API = "https://api.github.com/repos/nexarion666-png/Nexvio-/releases/latest"
private const val RELEASES_PAGE = "https://github.com/nexarion666-png/Nexvio-/releases/latest"

@Composable
fun NexvioUpdateChecker() {
    val context = LocalContext.current
    var updateTag by remember { mutableStateOf<String?>(null) }
    var releaseNotes by remember { mutableStateOf("") }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        updateTag = withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Nexvio-Android")
                }
                if (connection.responseCode !in 200..299) return@withContext null
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = release.optString("tag_name").removePrefix("v")
                if (tag.isNotBlank() && isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                    releaseNotes = release.optString("body").take(1000)
                    tag
                } else null
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
    }

    if (updateTag != null && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("Nexvio update available") },
            text = { Text("Version $updateTag is available. You have version ${BuildConfig.VERSION_NAME}.\n\n${releaseNotes.ifBlank { "Bug fixes and improvements." }}") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
                    dismissed = true
                }) { Text("Update now") }
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
