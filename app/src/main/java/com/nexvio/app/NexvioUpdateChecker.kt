package com.nexvio.app

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LATEST_UPDATE_JSON = "https://nexvio-updates-site-phi.vercel.app/latest.json"
private const val UPDATE_PREFS = "nexvio_update_state"
private const val UPDATE_DOWNLOAD_ID = "download_id"
private const val UPDATE_DISMISSED_VERSION = "dismissed_version"

private data class NexvioAvailableUpdate(
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String
)

@Composable
fun NexvioUpdateChecker() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) { context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE) }
    var checkTrigger by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<NexvioAvailableUpdate?>(null) }
    var downloadId by remember { mutableLongStateOf(prefs.getLong(UPDATE_DOWNLOAD_ID, -1L)) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var downloadedUri by remember { mutableStateOf<Uri?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

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
                if (version.isNotBlank() && url.startsWith("https://") &&
                    isNewerVersion(version, BuildConfig.VERSION_NAME)
                ) {
                    NexvioAvailableUpdate(version, json.optString("releaseNotes"), url)
                } else null
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
        update = latest
    }

    val available = update
    LaunchedEffect(available?.version, downloadId) {
        val target = available ?: return@LaunchedEffect
        if (downloadId <= 0L) return@LaunchedEffect
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val state = withContext(Dispatchers.IO) {
                val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
                cursor?.use {
                    if (!it.moveToFirst()) return@withContext Triple(-1, 0, null as Uri?)
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                    val uri = if (status == DownloadManager.STATUS_SUCCESSFUL) manager.getUriForDownloadedFile(downloadId) else null
                    Triple(status, percent, uri)
                } ?: Triple(-1, 0, null)
            }
            downloadProgress = state.second
            when (state.first) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadedUri = state.third
                    downloading = false
                    downloadError = if (state.third == null) "Download finished, but Android couldn't open the APK." else null
                    break
                }
                DownloadManager.STATUS_FAILED, -1 -> {
                    downloading = false
                    downloadError = "The download failed. Please try again."
                    break
                }
                else -> {
                    downloading = true
                    delay(600)
                }
            }
        }
    }

    if (available != null && prefs.getString(UPDATE_DISMISSED_VERSION, null) != available.version) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF20252B),
                shadowElevation = 12.dp
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nexvio update available", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            prefs.edit().putString(UPDATE_DISMISSED_VERSION, available.version).apply()
                        }) { Text("Later") }
                    }
                    Text("Version ${available.version} is ready. You have ${BuildConfig.VERSION_NAME}.", color = Color.LightGray, fontSize = 13.sp)
                    if (available.releaseNotes.isNotBlank()) {
                        Text(available.releaseNotes, color = Color.LightGray, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    when {
                        downloading -> {
                            LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("Downloading update… $downloadProgress%", color = Color.White, fontSize = 12.sp)
                        }
                        downloadedUri != null -> {
                            Text("Download complete. The update is ready to install.", color = Color(0xFF9FE3B1), fontSize = 12.sp)
                            Button(
                                onClick = {
                                    val apkUri = downloadedUri
                                    if (apkUri == null) {
                                        downloadError = "The downloaded APK could not be found. Please download it again."
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                        !context.packageManager.canRequestPackageInstalls()
                                    ) {
                                        val settingsIntent = Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(settingsIntent) }
                                            .onFailure { downloadError = "Please allow Nexvio to install unknown apps in Android Settings." }
                                    } else {
                                        runCatching {
                                            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                                                data = apkUri
                                                clipData = ClipData.newRawUri("Nexvio APK update", apkUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(installIntent)
                                        }.onFailure {
                                            downloadError = "Android couldn't open the installer. Check that Nexvio is allowed to install unknown apps, then try again."
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A36A))
                            ) { Text("Install update") }
                            downloadError?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp) }
                        }
                        else -> {
                            downloadError?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp) }
                            Button(
                                onClick = {
                                    downloadError = null
                                    downloadedUri = null
                                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val request = DownloadManager.Request(Uri.parse(available.downloadUrl))
                                        .setTitle("Nexvio ${available.version}")
                                        .setDescription("Downloading Nexvio update")
                                        .setMimeType("application/vnd.android.package-archive")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "nexvio-update-${available.version}.apk")
                                    downloadId = manager.enqueue(request)
                                    prefs.edit().putLong(UPDATE_DOWNLOAD_ID, downloadId).apply()
                                    downloading = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A36A))
                            ) { Text(if (downloadError == null) "Download update" else "Try download again") }
                        }
                    }
                }
            }
        }
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