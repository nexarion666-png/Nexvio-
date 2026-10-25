package com.nexvio.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.os.Environment
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
import kotlinx.coroutines.delay
import java.io.File
import java.net.URI

internal class OfflineDownloads(private val context: Context) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("nexvio_downloads", Context.MODE_PRIVATE)
    fun ids(): List<Long> = prefs.getString("ids", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }
    fun enqueue(rawUrl: String, rawTitle: String): Long {
        val parsed = URI(rawUrl.trim())
        require((parsed.scheme.equals("https", true) || parsed.scheme.equals("http", true)) && !parsed.host.isNullOrBlank()) { "This source does not provide a valid HTTP/HTTPS media URL." }
        val path = parsed.path.orEmpty().lowercase()
        require(!path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".m3u")) {
            "This source is a streaming playlist (HLS/DASH/M3U), not a direct media file. Nexvio cannot save it as an offline video yet."
        }
        require(listOf(".mp4", ".m4v", ".mkv", ".webm", ".mp3", ".aac").any { path.endsWith(it) } || !parsed.path.orEmpty().substringAfterLast("/").contains(".")) {
            "This URL does not look like a direct media file. Choose a direct MP4/M4V/MKV/WebM/MP3/AAC source."
        }
        val title = rawTitle.trim().ifBlank { parsed.path?.substringAfterLast('/')?.ifBlank { "Nexvio media" } ?: "Nexvio media" }
            .replace("/", "_").replace("\\", "_").take(100)
        val request = DownloadManager.Request(Uri.parse(parsed.toString()))
            .setTitle(title)
            .setDescription("Nexvio offline media")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, "Nexvio/$title")
        val id = manager.enqueue(request)
        prefs.edit().putString("ids", (ids() + id).distinct().joinToString(",")).apply()
        return id
    }
    fun rows(): List<Triple<Long, String, Int>> {
        val out = mutableListOf<Triple<Long, String, Int>>()
        ids().forEach { id ->
            val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return@forEach
            cursor.use {
                if (it.moveToFirst()) out += Triple(id, it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Media", it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
            }
        }
        return out
    }
    fun progress(id: Long): Pair<Long, Long>? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(-1L)
            return downloaded to total
        }
    }
    fun cancel(id: Long) { manager.remove(id) }
    fun open(id: Long): Uri? = manager.getUriForDownloadedFile(id)
    fun removeRecord(id: Long) { prefs.edit().putString("ids", ids().filterNot { it == id }.joinToString(",")).apply() }
    fun files(): List<File> { val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.resolve("Nexvio") ?: return emptyList(); return root.walkTopDown().filter { it.isFile && it.length() > 0 }.sortedByDescending { it.lastModified() }.toList() }
    fun delete(file: File) = runCatching { file.delete() }.getOrDefault(false)
}

internal fun enqueueDiscoveredStream(context: Context, title: String, url: String): Long =
    OfflineDownloads(context.applicationContext).enqueue(url, title)

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val store = remember { OfflineDownloads(context.applicationContext) }
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf(store.rows()) }
    var progressRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { rows = store.rows(); progressRefresh++; delay(1000) } }
    // Read this state so the screen recomposes even when queue status/title stay unchanged.
    val currentProgressRefresh = progressRefresh
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Downloads", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold); Text("Save supported direct media files for offline playback.", color = Color(0xFF9A9DA6)) }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1B1D22), RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("New download", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(title, { title = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it; error = null }, label = { Text("Direct media URL") }, placeholder = { Text("https://example.com/video.mp4") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Direct media files work best. Playlist URLs and DRM-protected streams are not converted into offline files.", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { try { store.enqueue(url, title); url = ""; title = ""; error = null; rows = store.rows() } catch (e: Exception) { error = e.message ?: "Could not start download." } }, modifier = Modifier.fillMaxWidth()) { Text("Start download") }
            }
        }
        item { Text("Download queue", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        if (rows.isEmpty()) item { Text("No downloads yet.", color = Color(0xFF9A9DA6)) }
        items(rows, key = { it.first }) { row ->
            
            Column(Modifier.fillMaxWidth().background(Color(0xFF1B1D22), RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.second, fontWeight = FontWeight.SemiBold)
                val transfer = store.progress(row.first)
                val downloadedBytes = transfer?.first ?: 0L
                val totalBytes = transfer?.second ?: -1L
                Text(when (row.third) { DownloadManager.STATUS_PENDING -> "Waiting"; DownloadManager.STATUS_RUNNING -> "Downloading"; DownloadManager.STATUS_PAUSED -> "Paused"; DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"; DownloadManager.STATUS_FAILED -> "Failed"; else -> "Checking status" }, color = Color(0xFF9A9DA6))
                if (row.third == DownloadManager.STATUS_RUNNING || row.third == DownloadManager.STATUS_PAUSED || row.third == DownloadManager.STATUS_PENDING) {
                    if (totalBytes > 0L) {
                        LinearProgressIndicator(progress = { (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFB7F36B))
                        Text("${downloadedBytes / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB • ${(downloadedBytes * 100 / totalBytes).coerceIn(0L, 100L)}%", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFB7F36B))
                        Text("${downloadedBytes / (1024 * 1024)} MB downloaded • total size unknown", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (row.third == DownloadManager.STATUS_SUCCESSFUL) Text("Open", color = Color(0xFFB7F36B), modifier = Modifier.clickable {
                        store.open(row.first)?.let { uri -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { error = "No compatible media player is installed." } }
                    })
                    if (row.third == DownloadManager.STATUS_PENDING || row.third == DownloadManager.STATUS_RUNNING || row.third == DownloadManager.STATUS_PAUSED) Text("Cancel", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { store.cancel(row.first); rows = store.rows() })
                    else Text("Remove from list", color = Color(0xFF9A9DA6), modifier = Modifier.clickable { store.removeRecord(row.first); rows = store.rows() })
                }
            }
        }
        item { Text("Android's Download Manager handles transfers and system notifications. Files are stored in Nexvio's app-managed Movies folder.", color = Color(0xFF9A9DA6), fontSize = 12.sp) }
    }
}

@Composable
fun OfflineLibraryScreen() {
    val context = LocalContext.current
    val store = remember { OfflineDownloads(context.applicationContext) }
    var files by remember { mutableStateOf(store.files()) }
    var rows by remember { mutableStateOf(store.rows()) }
    var progressRefresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { files = store.files(); rows = store.rows(); progressRefresh++; delay(1000) } }
    // Download byte counts change independently of queue status, so force a periodic recomposition.
    val currentProgressRefresh = progressRefresh
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Offline Library", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("Downloaded media available without internet.", color = Color(0xFF9A9DA6))
            Text("${files.size} file(s) • ${files.sumOf { it.length() } / (1024 * 1024)} MB used", color = Color(0xFFB7F36B))
        }
        message?.let { item { Text(it, color = Color(0xFFB7F36B)) } }
        item { Text("DOWNLOADS IN PROGRESS", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        val activeRows = rows.filter { it.third == DownloadManager.STATUS_PENDING || it.third == DownloadManager.STATUS_RUNNING || it.third == DownloadManager.STATUS_PAUSED }
        if (activeRows.isEmpty()) item { Text("No active downloads.", color = Color(0xFF9A9DA6), fontSize = 13.sp) }
        items(activeRows, key = { "download-${it.first}" }) { row ->
            val transfer = store.progress(row.first)
            val downloadedBytes = transfer?.first ?: 0L
            val totalBytes = transfer?.second ?: -1L
            Column(Modifier.fillMaxWidth().background(Color(0xFF1B1D22), RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.second, fontWeight = FontWeight.SemiBold)
                Text(when (row.third) { DownloadManager.STATUS_PENDING -> "Waiting"; DownloadManager.STATUS_RUNNING -> "Downloading"; DownloadManager.STATUS_PAUSED -> "Paused"; else -> "In progress" }, color = Color(0xFF9A9DA6), fontSize = 12.sp)
                if (totalBytes > 0L) {
                    LinearProgressIndicator(progress = { (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFB7F36B))
                    Text("${downloadedBytes / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB • ${(downloadedBytes * 100 / totalBytes).coerceIn(0L, 100L)}%", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFB7F36B))
                    Text("${downloadedBytes / (1024 * 1024)} MB downloaded • total size unknown", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                }
                Text("Cancel", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { store.cancel(row.first); rows = store.rows() })
            }
        }
        item { Text("OFFLINE FILES", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        if (files.isEmpty()) item { Text("Nothing offline yet. Open Movies or Series, find a stream, then long-press a supported direct media source to download it.", color = Color(0xFF9A9DA6)) }
        items(files, key = { it.absolutePath }) { file ->
            Row(Modifier.fillMaxWidth().background(Color(0xFF1B1D22), RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.name, fontWeight = FontWeight.SemiBold)
                    Text("${file.length() / (1024 * 1024)} MB • ${file.extension.uppercase()}", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                }
                Text("Play", color = Color(0xFFB7F36B), modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file), "video/*")) }.onFailure { message = "No compatible video player could open this file." }
                })
                Spacer(Modifier.width(12.dp))
                Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { if (store.delete(file)) { files = store.files(); message = "File deleted." } else message = "Could not delete file." })
            }
        }
    }
}
