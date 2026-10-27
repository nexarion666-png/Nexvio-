package com.nexvio.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import coil.compose.AsyncImage
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
fun DownloadsScreen(onPlay: (TvChannel) -> Unit) {
    val context = LocalContext.current
    val store = remember { OfflineDownloads(context.applicationContext) }
    var rows by remember { mutableStateOf(store.rows()) }
    var error by remember { mutableStateOf<String?>(null) }
    var progressRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { rows = store.rows(); progressRefresh++; delay(1000) } }
    // Read this state so the screen recomposes even when queue status/title stay unchanged.
    val currentProgressRefresh = progressRefresh
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Downloads", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold); Text("Save supported direct media files for offline playback.", color = Color(0xFF9A9DA6)) }
        item { Text("Download queue", fontSize = 20.sp, fontWeight = FontWeight.Bold); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (rows.isEmpty()) item { Text("No downloads yet.", color = Color(0xFF9A9DA6)) }
        items(rows, key = { it.first }) { row ->
            
            Column(Modifier.fillMaxWidth().background(Color(0xFF1B1D22), RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.second, fontWeight = FontWeight.SemiBold)
                val transfer = remember(row.first, progressRefresh) { store.progress(row.first) }
                val downloadedBytes = transfer?.first ?: 0L
                val totalBytes = transfer?.second ?: -1L
                Text(when (row.third) { DownloadManager.STATUS_PENDING -> "Waiting"; DownloadManager.STATUS_RUNNING -> "Downloading"; DownloadManager.STATUS_PAUSED -> "Paused"; DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"; DownloadManager.STATUS_FAILED -> "Failed"; else -> "Checking status" }, color = Color(0xFF9A9DA6))
                if (row.third == DownloadManager.STATUS_RUNNING || row.third == DownloadManager.STATUS_PAUSED || row.third == DownloadManager.STATUS_PENDING) {
                    if (totalBytes > 0L) {
                        LinearProgressIndicator(progress = { (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF8B7CFF))
                        Text("${downloadedBytes / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB • ${(downloadedBytes * 100 / totalBytes).coerceIn(0L, 100L)}%", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFB7F36B))
                        Text("${downloadedBytes / (1024 * 1024)} MB downloaded • total size unknown", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (row.third == DownloadManager.STATUS_SUCCESSFUL) Text("Play", color = Color(0xFFB7F36B), modifier = Modifier.clickable {
                        val uri = store.open(row.first)
                        if (uri == null) {
                            error = "The downloaded file could not be opened. It may have been removed."
                        } else {
                            // Play local content in the same Media3 player used by streams and live TV.
                            onPlay(TvChannel(
                                id = "offline-download-${row.first}",
                                name = row.second,
                                streamUrl = uri.toString(),
                                category = "offline",
                                tvgName = row.second
                            ))
                        }
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
fun OfflineLibraryScreen(onPlay: (TvChannel) -> Unit) {
    val context = LocalContext.current
    val watchlist = remember(context) { WatchlistStore(context) }
    var saved by remember { mutableStateOf(watchlist.all()) }
    var showDownloads by remember { mutableStateOf(false) }
    var cloudSelected by remember { mutableStateOf(false) }
    var gridMode by remember { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortByRecent by remember { mutableStateOf(true) }
    if (showDownloads) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0D0D0F))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable { showDownloads = false }.padding(end = 14.dp))
                Text("Downloads", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            DownloadsScreen(onPlay = onPlay)
        }
        return
    }
    val titles = if (sortByRecent) saved.reversed() else saved.sortedBy { it.title.lowercase() }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0D0F))) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", modifier = Modifier.weight(1f), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (gridMode) "▦" else "☰", color = Color(0xFF9A9DA6), fontSize = 28.sp, modifier = Modifier.clickable { gridMode = !gridMode }.padding(horizontal = 12.dp))
            Text("⇩", color = Color(0xFF9A9DA6), fontSize = 30.sp, modifier = Modifier.clickable { showDownloads = true }.padding(start = 12.dp, end = 4.dp))
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.background(if (!cloudSelected) Color(0xFF303034) else Color(0xFF19191D), RoundedCornerShape(28.dp)).clickable { cloudSelected = false }.padding(horizontal = 22.dp, vertical = 10.dp)) { Text("Saved", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.background(if (cloudSelected) Color(0xFF303034) else Color(0xFF19191D), RoundedCornerShape(28.dp)).clickable { cloudSelected = true }.padding(horizontal = 22.dp, vertical = 10.dp)) { Text("Cloud", color = Color(0xFF9A9DA6), fontSize = 14.sp) }
        }
        if (cloudSelected) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Cloud sync isn't connected yet.", color = Color(0xFF9A9DA6)) }
        else {
            Box(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 20.dp)) {
                Row(Modifier.background(Color(0xFF19191D), RoundedCornerShape(14.dp)).clickable { sortMenu = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sortByRecent) "Recently added" else "Name", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(" ⌄", color = Color(0xFF9A9DA6), fontSize = 18.sp)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    DropdownMenuItem(text = { Text("Recently added") }, onClick = { sortByRecent = true; sortMenu = false })
                    DropdownMenuItem(text = { Text("Name") }, onClick = { sortByRecent = false; sortMenu = false })
                }
            }
            if (titles.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your watchlist is empty", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text("Add movies and series from their detail pages.", color = Color(0xFF9A9DA6))
            } } else if (gridMode) {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(titles, key = { it.mediaType + "-" + it.id }) { item ->
                        Column(Modifier.fillMaxWidth()) {
                            AsyncImage(model = item.posterPath?.let { "https://image.tmdb.org/t/p/w500" + it }, contentDescription = item.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(248.dp).background(Color(0xFF19191D), RoundedCornerShape(12.dp)))
                            Spacer(Modifier.height(8.dp)); Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text((if (item.mediaType == "movie") "Movie" else "Series") + if (item.year.isNotBlank()) " • " + item.year else "", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                            Text("▣", color = Color(0xFFCF9DA4), fontSize = 20.sp, modifier = Modifier.clickable { watchlist.remove(item); saved = watchlist.all() }.padding(top = 5.dp))
                        }
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(titles, key = { it.mediaType + "-" + it.id }) { item ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFF19191D), RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = item.posterPath?.let { "https://image.tmdb.org/t/p/w500" + it }, contentDescription = item.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(width = 68.dp, height = 96.dp).background(Color(0xFF26262A), RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(if (item.mediaType == "movie") "Movie" else "Series", color = Color(0xFF9A9DA6), fontSize = 12.sp) }
                        Text("▣", color = Color(0xFFCF9DA4), modifier = Modifier.clickable { watchlist.remove(item); saved = watchlist.all() })
                    }
                }
            }
        }
    }
}
