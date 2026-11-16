package com.nexvio.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LATEST_UPDATE_JSON = "https://nexvio-updates-site-phi.vercel.app/latest.json"
private const val UPDATE_PREFS = "nexvio_update_download"
private const val UPDATE_ID_KEY = "download_id"

@Composable
fun NexvioUpdateChecker() {
    val context = LocalContext.current
    val downloadManager = remember {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    var updateVersion by remember { mutableStateOf<String?>(null) }
    var releaseNotes by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var downloadId by remember {
        mutableLongStateOf(
            context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .getLong(UPDATE_ID_KEY, -1L)
        )
    }
    var progress by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var downloadComplete by remember { mutableStateOf(false) }
    var downloadFailed by remember { mutableStateOf(false) }

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
                if (version.isNotBlank() && url.startsWith("https://") &&
                    isNewerVersion(version, BuildConfig.VERSION_NAME)
                ) Triple(version, json.optString("releaseNotes"), url) else null
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

    LaunchedEffect(downloadId) {
        if (downloadId == -1L) return@LaunchedEffect
        downloading = true
        downloadFailed = false
        while (true) {
            val result = withContext(Dispatchers.IO) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        Triple(status, total, soFar)
                    }
                }
            }
            if (result == null) {
                downloading = false
                downloadFailed = true
                break
            }
            val (status, total, soFar) = result
            progress = if (total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else 0
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress = 100
                    downloading = false
                    downloadComplete = true
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    downloading = false
                    downloadFailed = true
                    break
                }
                else -> {
                    downloading = true
                    delay(350)
                }
            }
        }
    }

    if (updateVersion != null && downloadUrl != null && !dismissed) {
        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 6.dp
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Nexvio update available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Version $updateVersion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { dismissed = true }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss update")
                        }
                    }
                    if (releaseNotes.isNotBlank()) {
                        Text(releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        downloading -> {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("Downloading update… $progress%", style = MaterialTheme.typography.labelMedium)
                        }
                        downloadComplete -> {
                            Text("Download complete", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { installDownloadedUpdate(context, downloadManager, downloadId) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Install")
                            }
                        }
                        else -> {
                            if (downloadFailed) Text("Download failed. Please try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = {
                                    val request = DownloadManager.Request(Uri.parse(downloadUrl))
                                        .setTitle("Nexvio $updateVersion")
                                        .setDescription("Downloading Nexvio update")
                                        .setMimeType("application/vnd.android.package-archive")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                                        .setAllowedOverMetered(true)
                                        .setAllowedOverRoaming(true)
                                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "nexvio-update-$updateVersion.apk")
                                    val newId = downloadManager.enqueue(request)
                                    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                                        .edit().putLong(UPDATE_ID_KEY, newId).apply()
                                    downloadId = newId
                                    progress = 0
                                    downloadComplete = false
                                    downloadFailed = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors()
                            ) { Text("Download") }
                        }
                    }
                }
            }
        }
    }
}

private fun installDownloadedUpdate(context: Context, manager: DownloadManager, id: Long) {
    if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)
        return
    }
    val apkUri = manager.getUriForDownloadedFile(id) ?: return
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
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
