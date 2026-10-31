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
import androidx.compose.ui.draw.clip
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch

private const val DOWNLOADS_TMDB_API_KEY = "df35ad7483eaf39d05d6b534109453bb"
private const val DOWNLOADS_TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
private suspend fun findDownloadPoster(title: String, mediaType: String): String? = withContext(Dispatchers.IO) {
    val type = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
    val query = URLEncoder.encode(title, "UTF-8")
    val connection = URL("https://api.themoviedb.org/3/search/$type?api_key=$DOWNLOADS_TMDB_API_KEY&query=$query").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        if (connection.responseCode !in 200..299) return@withContext null
        val results = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: return@withContext null
        val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("poster_path").startsWith("/") }
        val exact = candidates.firstOrNull {
            it.optString(if (type == "tv") "name" else "title").trim().equals(title.trim(), ignoreCase = true)
        }
        val path = (exact ?: candidates.firstOrNull())?.optString("poster_path").orEmpty()
        if (path.startsWith("/")) DOWNLOADS_TMDB_IMAGE_BASE + path else null
    } catch (_: Exception) { null } finally { connection.disconnect() }
}

/** One key per folder on the Downloads screen, e.g. "movie:inception" or "tv:breaking bad". */
internal fun downloadGroupKey(mediaType: String, groupTitle: String): String =
    (if (mediaType == "tv") "tv:" else "movie:") + groupTitle.lowercase()

/** Looks for a poster path in caches that already live on the phone (watchlist, saved TMDB lists). No network. */
private suspend fun knownPosterUrl(context: Context, profileId: String, title: String, type: String): String? = withContext(Dispatchers.IO) {
    val wanted = title.trim()
    val path = runCatching {
        WatchlistStore(context, profileId).all()
            .firstOrNull { it.mediaType == type && it.posterPath != null && it.title.trim().equals(wanted, ignoreCase = true) }?.posterPath
            ?: TmdbOfflineCache.searchOffline(type, wanted)
                .firstOrNull { it.posterPath != null && it.title.trim().equals(wanted, ignoreCase = true) }?.posterPath
    }.getOrNull()
    path?.let { DOWNLOADS_TMDB_IMAGE_BASE + it }
}

internal class OfflineDownloads(private val context: Context, private val profileId: String = NexvioProfileStore.active(context).id) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = ResumableDownloads.prefs(context, profileId)
    fun ids(): List<Long> = prefs.getString("ids", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }
    fun enqueue(rawUrl: String, rawTitle: String, groupTitle: String = rawTitle, mediaType: String = "movie", posterUrl: String? = null): Long {
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
        // New downloads use the resumable engine so they can be paused and resumed.
        val id = ResumableDownloads.enqueue(context, profileId, parsed.toString(), title)
        prefs.edit().putString("ids", (ids() + id).distinct().joinToString(",")).apply()
        val metadata = org.json.JSONObject()
            .put("groupTitle", groupTitle)
            .put("mediaType", mediaType)
            .put("posterUrl", posterUrl.orEmpty())
            .put("downloadTitle", title)
        prefs.edit().putString("metadata_$id", metadata.toString()).apply()
        // Save the poster to the phone right away so the folder still has it offline.
        if (!posterUrl.isNullOrBlank()) {
            val app = context.applicationContext
            val key = downloadGroupKey(mediaType, groupTitle)
            ResumableDownloads.scope.launch { DownloadPosterStore.fetchAndSave(app, key, posterUrl) }
        }
        return id
    }
    fun rows(): List<Triple<Long, String, Int>> {
        val all = ids()
        ResumableDownloads.reconcile(prefs, profileId, all)
        val out = mutableListOf<Triple<Long, String, Int>>()
        all.forEach { id ->
            if (ResumableDownloads.isOwn(id)) {
                ResumableDownloads.row(prefs, id)?.let { out += it }
                return@forEach
            }
            val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return@forEach
            cursor.use {
                if (it.moveToFirst()) out += Triple(id, it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Media", it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
            }
        }
        return out
    }
    fun progress(id: Long): Pair<Long, Long>? {
        if (ResumableDownloads.isOwn(id)) return ResumableDownloads.progress(context, profileId, id)
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(-1L)
            return downloaded to total
        }
    }
    fun errorMessage(id: Long): String? =
        if (ResumableDownloads.isOwn(id)) ResumableDownloads.record(prefs, id)?.optString("error")?.takeIf { it.isNotBlank() } else null
    fun pause(id: Long) { if (ResumableDownloads.isOwn(id)) ResumableDownloads.pause(context, profileId, id) }
    fun resume(id: Long) { if (ResumableDownloads.isOwn(id)) ResumableDownloads.resume(context, profileId, id) }
    /** Stops the transfer if it is running, deletes the video file from the phone and forgets the entry. */
    fun deleteDownload(id: Long) {
        if (ResumableDownloads.isOwn(id)) {
            ResumableDownloads.delete(context, profileId, id)
        } else {
            val file = open(id)?.path?.let { File(it) }
            runCatching { manager.remove(id) }
            file?.let { runCatching { it.delete() } }
        }
        removeRecord(id)
    }
    fun cancel(id: Long) = deleteDownload(id)
    fun open(id: Long): Uri? {
        if (ResumableDownloads.isOwn(id)) return ResumableDownloads.completedFile(context, profileId, id)?.let { Uri.fromFile(it) }
        // DownloadManager's getUriForDownloadedFile() returns a provider URI such as
        // content://downloads/all_downloads/884 on some Android versions. Media3 cannot
        // necessarily read that URI because DownloadProvider does not grant this app
        // access to that internal all_downloads URI. These downloads are deliberately
        // saved in this app's own external-files Movies directory, so play the actual
        // app-owned file instead.
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null

            val localUriIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val localUri = if (localUriIndex >= 0) it.getString(localUriIndex) else null
            if (!localUri.isNullOrBlank()) {
                val parsed = Uri.parse(localUri)
                if (parsed.scheme.equals("file", ignoreCase = true)) {
                    val file = File(parsed.path.orEmpty())
                    if (file.isFile && file.canRead()) return Uri.fromFile(file)
                }
            }

            // COLUMN_LOCAL_FILENAME is deprecated on newer Android releases, but where
            // provided it gives us the real path without needing DownloadProvider access.
            val filenameIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
            val filename = if (filenameIndex >= 0) it.getString(filenameIndex) else null
            if (!filename.isNullOrBlank()) {
                val file = File(filename)
                if (file.isFile && file.canRead()) return Uri.fromFile(file)
            }

            // Last-resort path for downloads created by Nexvio: setDestinationInExternalFilesDir
            // stores the file under Movies/Nexvio[/<profileId>]/<sanitized title>.
            val titleIndex = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val title = if (titleIndex >= 0) it.getString(titleIndex) else null
            if (!title.isNullOrBlank()) {
                val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val root = if (profileId == "primary") base?.resolve("Nexvio")
                    else base?.resolve("Nexvio")?.resolve(profileId)
                val safeTitle = title.replace("/", "_").replace("\\", "_").take(100)
                val file = root?.resolve(safeTitle)
                if (file != null && file.isFile && file.canRead()) return Uri.fromFile(file)
            }
        }
        return null
    }
    fun metadata(id: Long): org.json.JSONObject = runCatching {
        org.json.JSONObject(prefs.getString("metadata_$id", "{}").orEmpty())
    }.getOrElse { org.json.JSONObject() }
    fun removeRecord(id: Long) { prefs.edit().remove("metadata_$id").putString("ids", ids().filterNot { it == id }.joinToString(",")).apply() }
    fun files(): List<File> { val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.resolve("Nexvio") ?: return emptyList(); val root = if (profileId == "primary") base else base.resolve(profileId); return root.walkTopDown().filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }.sortedByDescending { it.lastModified() }.toList() }
    fun delete(file: File) = runCatching { file.delete() }.getOrDefault(false)
}

internal fun enqueueDiscoveredStream(context: Context, title: String, url: String, profileId: String = NexvioProfileStore.active(context).id, groupTitle: String = title, mediaType: String = "movie", posterUrl: String? = null): Long =
    OfflineDownloads(context.applicationContext, profileId).enqueue(url, title, groupTitle, mediaType, posterUrl)

@Composable
private fun DownloadActionPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF2A2D34)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFB7F36B), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color(0xFFB7F36B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * One film / episode inside a folder.
 * Tap = play (when finished). Long press = Delete menu. While downloading: progress bar + Pause / Resume.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadItemCard(
    title: String,
    status: Int,
    transfer: Pair<Long, Long>?,
    errorText: String?,
    poster: Any?,
    canPause: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val finished = status == DownloadManager.STATUS_SUCCESSFUL
    val running = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
    val stopped = status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_FAILED
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1D22))
                .combinedClickable(
                    onClick = { if (finished) onPlay() },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true }
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 70.dp, height = 100.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF292B31))) {
                if (poster != null) AsyncImage(model = poster, contentDescription = title, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("▣", color = Color(0xFF777B86), fontSize = 28.sp) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val label = when (status) {
                    DownloadManager.STATUS_PENDING -> "Waiting"
                    DownloadManager.STATUS_RUNNING -> "Downloading"
                    DownloadManager.STATUS_PAUSED -> "Paused"
                    DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"
                    DownloadManager.STATUS_FAILED -> "Failed"
                    else -> "Checking status"
                }
                Text(
                    if (stopped && !errorText.isNullOrBlank()) "$label — $errorText" else label,
                    color = Color(0xFF9A9DA6), fontSize = 13.sp
                )
                if (!finished) {
                    val done = transfer?.first ?: 0L
                    val total = transfer?.second ?: -1L
                    val mb = 1024L * 1024L
                    if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (total > 0) "${done / mb} MB / ${total / mb} MB • ${(done * 100 / total).coerceIn(0L, 100L)}%" else "${done / mb} MB downloaded",
                        color = Color(0xFF9A9DA6), fontSize = 12.sp
                    )
                    if (canPause && running) DownloadActionPill(Icons.Filled.Pause, "Pause", onPause)
                    if (canPause && stopped) DownloadActionPill(Icons.Filled.PlayArrow, if (status == DownloadManager.STATUS_FAILED) "Retry" else "Resume", onResume)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete() }
            )
        }
    }
}

@Composable
fun DownloadsScreen(onPlay: (TvChannel) -> Unit, profileId: String = NexvioProfileStore.active(LocalContext.current).id, onPlayQueue: ((List<TvChannel>, Int) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember(context, profileId) { OfflineDownloads(context.applicationContext, profileId) }
    var rows by remember { mutableStateOf(store.rows()) }
    var error by remember { mutableStateOf<String?>(null) }
    var progressRefresh by remember { mutableIntStateOf(0) }
    var openedFolder by remember { mutableStateOf<String?>(null) }
    // Bumped whenever a poster file has just been saved, so the screen re-reads it from disk.
    var posterVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { rows = store.rows(); progressRefresh++; delay(1000) } }
    val groups = remember(rows, progressRefresh) {
        rows.groupBy { row ->
            val meta = store.metadata(row.first)
            val looksLikeEpisode = Regex("(?i)\\bS\\d{1,3}\\s*E\\d{1,4}\\b").containsMatchIn(row.second)
            val type = if (looksLikeEpisode) "tv" else meta.optString("mediaType", "movie")
            val title = meta.optString("groupTitle").takeIf { it.isNotBlank() }
                ?: row.second.replace(Regex("(?i)\\s+-\\s+S\\d{1,2}E\\d{1,3}.*$"), "").trim()
            (if (type == "tv") "tv:" else "movie:") + title.lowercase()
        }
    }
    // Make sure every folder has its poster saved on the phone. Covers downloads made before this
    // update too: the first time the app is opened with a connection (or with the poster still in
    // the image cache) the poster is saved, and from then on it shows offline.
    val groupKeys = groups.keys.toList()
    LaunchedEffect(groupKeys) {
        for (key in groupKeys) {
            val groupRows = groups[key] ?: continue
            if (DownloadPosterStore.local(context, key) != null) continue
            val meta = store.metadata(groupRows.first().first)
            val first = groupRows.first()
            val title = meta.optString("groupTitle").takeIf { it.isNotBlank() }
                ?: first.second.replace(Regex("(?i)\\s+-\\s+S\\d{1,2}E\\d{1,3}.*$"), "").trim()
            val type = if (key.startsWith("tv:")) "tv" else "movie"
            val url = meta.optString("posterUrl").takeIf { it.startsWith("https://") }
                ?: knownPosterUrl(context, profileId, title, type)
                ?: findDownloadPoster(title, type)
            if (url != null && DownloadPosterStore.fetchAndSave(context.applicationContext, key, url) != null) posterVersion++
        }
    }
    val selectedRows = openedFolder?.let { groups[it] }
    // If the last item in an opened folder was deleted, go back to the folder list.
    LaunchedEffect(selectedRows == null, openedFolder) { if (openedFolder != null && selectedRows == null) openedFolder = null }
    if (openedFolder != null && selectedRows != null) {
        val folderKey = openedFolder ?: ""
        val isTvFolder = folderKey.startsWith("tv:")
        val first = selectedRows.firstOrNull()
        val title = first?.let { store.metadata(it.first).optString("groupTitle").takeIf(String::isNotBlank) } ?: first?.second ?: "Downloads"
        val posterModel: Any? = remember(folderKey, posterVersion) {
            DownloadPosterStore.local(context, folderKey)
                ?: first?.let { store.metadata(it.first).optString("posterUrl").takeIf { url -> url.startsWith("https://") } }
        }
        fun playRow(row: Triple<Long, String, Int>) {
            val orderedRows = if (isTvFolder) selectedRows.filter { it.third == DownloadManager.STATUS_SUCCESSFUL }.sortedWith(
                compareBy<Triple<Long, String, Int>>(
                    { Regex("(?i)S(\\d{1,3})E(\\d{1,4})").find(it.second)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                    { Regex("(?i)S(\\d{1,3})E(\\d{1,4})").find(it.second)?.groupValues?.get(2)?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.second.lowercase() }
                )
            ) else listOf(row)
            val queue = orderedRows.mapNotNull { episode ->
                store.open(episode.first)?.let { uri ->
                    TvChannel(id = "offline-download-${episode.first}", name = episode.second, streamUrl = uri.toString(), category = "offline", tvgName = episode.second)
                }
            }
            if (queue.isEmpty()) error = "The downloaded file could not be opened."
            else {
                error = null
                val index = queue.indexOfFirst { it.id == "offline-download-${row.first}" }.coerceAtLeast(0)
                if (isTvFolder && onPlayQueue != null) onPlayQueue(queue, index) else onPlay(queue[index])
            }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF0D0D0F))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable { openedFolder = null }.padding(end = 14.dp))
                Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Text("Tap to play • Hold to delete", color = Color(0xFF777B86), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(selectedRows, key = { it.first }) { row ->
                    DownloadItemCard(
                        title = row.second,
                        status = row.third,
                        transfer = store.progress(row.first),
                        errorText = store.errorMessage(row.first),
                        poster = posterModel,
                        canPause = ResumableDownloads.isOwn(row.first),
                        onPlay = { playRow(row) },
                        onPause = { store.pause(row.first); rows = store.rows() },
                        onResume = { store.resume(row.first); rows = store.rows() },
                        onDelete = {
                            store.deleteDownload(row.first)
                            if (selectedRows.size <= 1) DownloadPosterStore.delete(context, folderKey)
                            rows = store.rows()
                        }
                    )
                }
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Downloads", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("Your movies and TV shows, organized by title.", color = Color(0xFF9A9DA6))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (groups.isEmpty()) item { Text("No downloads yet.", color = Color(0xFF9A9DA6)) }
        items(groups.entries.toList(), key = { it.key }) { entry ->
            val groupRows = entry.value
            val meta = store.metadata(groupRows.first().first)
            val title = meta.optString("groupTitle").takeIf { it.isNotBlank() }
                ?: groupRows.first().second.replace(Regex("(?i)\\s+-\\s+S\\d{1,2}E\\d{1,3}.*$"), "").trim()
            // Prefer the poster file saved on the phone; fall back to the online URL.
            val poster: Any? = remember(entry.key, posterVersion) { DownloadPosterStore.local(context, entry.key) }
                ?: meta.optString("posterUrl").takeIf { it.startsWith("https://") }
            val isShow = entry.key.startsWith("tv:")
            Row(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1D22)).clickable { openedFolder = entry.key }) {
                Box(Modifier.width(126.dp).fillMaxHeight().background(Color(0xFF292B31))) {
                    if (poster != null) AsyncImage(model=poster, contentDescription=title, contentScale=androidx.compose.ui.layout.ContentScale.Crop, modifier=Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) { Text("▣", color=Color(0xFF777B86), fontSize=42.sp) }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(14.dp), verticalArrangement=Arrangement.Center) {
                    Text(title, color=Color.White, fontSize=18.sp, fontWeight=FontWeight.Bold, maxLines=2)
                    Spacer(Modifier.height(8.dp))
                    Text(if (isShow) "${groupRows.size} downloaded episode${if (groupRows.size == 1) "" else "s"}" else "Movie", color=Color(0xFF9A9DA6), fontSize=13.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Open folder  ›", color=Color(0xFFB7F36B), fontWeight=FontWeight.SemiBold)
                }
            }
        }
        item { Text("Downloads are stored in Nexvio's app-managed Movies folder.", color=Color(0xFF9A9DA6), fontSize=12.sp) }
    }
}

 @Composable
internal fun OfflineLibraryScreen(onPlay: (TvChannel) -> Unit, profileId: String = NexvioProfileStore.active(LocalContext.current).id, onPlayQueue: ((List<TvChannel>, Int) -> Unit)? = null, onTitleSelected: ((TmdbTitle) -> Unit)? = null) {
    val context = LocalContext.current
    val watchlist = remember(context, profileId) { WatchlistStore(context, profileId) }
    var saved by remember { mutableStateOf(watchlist.all()) }
    LaunchedEffect(saved) { TmdbOfflineCache.prefetchPosters(saved.mapNotNull { it.posterPath }) }
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
            DownloadsScreen(onPlay = onPlay, profileId = profileId, onPlayQueue = onPlayQueue)
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
                        Column(Modifier.fillMaxWidth().clickable { onTitleSelected?.invoke(item) }) {
                            AsyncImage(model = item.posterPath?.let { "https://image.tmdb.org/t/p/w500" + it }, contentDescription = item.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(248.dp).background(Color(0xFF19191D), RoundedCornerShape(12.dp)))
                            Spacer(Modifier.height(8.dp)); Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text((if (item.mediaType == "movie") "Movie" else "Series") + if (item.year.isNotBlank()) " • " + item.year else "", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                            Text("▣", color = Color(0xFFCF9DA4), fontSize = 20.sp, modifier = Modifier.clickable { watchlist.remove(item); saved = watchlist.all() }.padding(top = 5.dp))
                        }
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(titles, key = { it.mediaType + "-" + it.id }) { item ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFF19191D), RoundedCornerShape(14.dp)).clickable { onTitleSelected?.invoke(item) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = item.posterPath?.let { "https://image.tmdb.org/t/p/w500" + it }, contentDescription = item.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(width = 68.dp, height = 96.dp).background(Color(0xFF26262A), RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(if (item.mediaType == "movie") "Movie" else "Series", color = Color(0xFF9A9DA6), fontSize = 12.sp) }
                        Text("▣", color = Color(0xFFCF9DA4), modifier = Modifier.clickable { watchlist.remove(item); saved = watchlist.all() })
                    }
                }
            }
        }
    }
}
