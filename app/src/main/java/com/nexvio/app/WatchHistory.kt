package com.nexvio.app

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class WatchEntry(
    val id: String,
    val title: String,
    val streamUrl: String,
    val posterUrl: String?,
    val mediaType: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val progress: Float get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val completed: Boolean get() = durationMs > 0L && positionMs >= durationMs * 0.92f
    fun toChannel() = TvChannel(
        id = id,
        name = title,
        streamUrl = streamUrl,
        logoUrl = posterUrl,
        category = mediaType,
        tvgName = title
    )
}

internal class WatchHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nexvio_watch_history", Context.MODE_PRIVATE)
    private val key = "entries"
    @Synchronized fun all(): List<WatchEntry> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WatchEntry(id, title, url, o.optString("poster").takeIf { it.isNotBlank() && it != "null" },
                    o.optString("type", "movie"), o.optLong("position", 0L).coerceAtLeast(0L),
                    o.optLong("duration", 0L).coerceAtLeast(0L), o.optLong("updated", 0L))
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }
    @Synchronized fun get(id: String): WatchEntry? = all().firstOrNull { it.id == id }
    @Synchronized fun record(channel: TvChannel, positionMs: Long = 0L, durationMs: Long = 0L) {
        // Live IPTV channels are not personal movie/series viewing history.
        if (!channel.id.startsWith("addon-")) return
        val old = get(channel.id)
        val type = when {
            channel.id.startsWith("addon-tv-") -> "series"
            channel.id.startsWith("addon-movie-") -> "movie"
            else -> old?.mediaType ?: "movie"
        }
        val entry = WatchEntry(channel.id, channel.name, channel.streamUrl, channel.logoUrl, type,
            positionMs.coerceAtLeast(0L), durationMs.coerceAtLeast(0L), System.currentTimeMillis())
        val entries = (all().filterNot { it.id == entry.id } + entry).sortedByDescending { it.updatedAt }.take(100)
        save(entries)
    }
    @Synchronized fun remove(id: String) = save(all().filterNot { it.id == id })
    @Synchronized fun clear() = prefs.edit().remove(key).apply()
    private fun save(entries: List<WatchEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().put("id", e.id).put("title", e.title).put("url", e.streamUrl)
                .put("poster", e.posterUrl ?: JSONObject.NULL).put("type", e.mediaType)
                .put("position", e.positionMs).put("duration", e.durationMs).put("updated", e.updatedAt))
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

@Composable
internal fun WatchHistorySection(
    title: String,
    entries: List<WatchEntry>,
    onPlay: (TvChannel) -> Unit,
    onSeeAll: (() -> Unit)? = null
) {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            androidx.compose.material3.Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (onSeeAll != null) androidx.compose.material3.Text(
                "See all", color = Color(0xFFB7F36B),
                modifier = androidx.compose.ui.Modifier.clickable { onSeeAll() }
            )
        }
        if (entries.isEmpty()) {
            androidx.compose.material3.Text(
                "Movies and series you play will appear here.",
                color = Color(0xFF9A9DA6), fontSize = 13.sp
            )
        } else entries.forEach { entry ->
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    .background(Color(0xFF1B1D22), RoundedCornerShape(14.dp))
                    .clickable { onPlay(entry.toChannel()) }.padding(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                coil.compose.AsyncImage(
                    model = entry.posterUrl,
                    contentDescription = entry.title,
                    modifier = androidx.compose.ui.Modifier.width(58.dp).height(78.dp)
                        .background(Color(0xFF101114), RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    androidx.compose.material3.Text(
                        if (entry.completed) "Watched" else if (entry.positionMs > 0L) "Continue watching" else entry.mediaType.replaceFirstChar { it.uppercase() },
                        color = Color(0xFF9A9DA6), fontSize = 12.sp
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    if (entry.durationMs > 0L) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { entry.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Color(0xFFB7F36B),
                            trackColor = Color(0xFF393C43)
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Text("▶", color = Color(0xFFB7F36B))
            }
        }
    }
}

@Composable
internal fun WatchHistoryScreen(onPlay: (TvChannel) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { WatchHistoryStore(context) }
    var entries by remember { mutableStateOf(store.all()) }
    var showClearDialog by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            entries = store.all()
            kotlinx.coroutines.delay(1500)
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
    ) {
        item {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                androidx.compose.foundation.layout.Column {
                    Text("Watch history", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Your viewing progress stays on this device.", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                }
                if (entries.isNotEmpty()) TextButton(onClick = { showClearDialog = true }) { Text("Clear all", color = Color(0xFFB7F36B)) }
            }
        }
        if (entries.isEmpty()) item {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().background(Color(0xFF1B1D22), RoundedCornerShape(16.dp)).padding(20.dp)
            ) {
                Text("Nothing watched yet", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Play a movie or series from the catalogue and it will appear here.", color = Color(0xFF9A9DA6))
            }
        }
        items(entries, key = { it.id }) { entry ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().background(Color(0xFF1B1D22), RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f).clickable { onPlay(entry.toChannel()) }) {
                    Text(entry.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (entry.completed) "Completed" else if (entry.positionMs > 0L) "Resume at " + formatWatchTime(entry.positionMs) else "Started watching",
                        color = Color(0xFF9A9DA6), fontSize = 12.sp
                    )
                    if (entry.durationMs > 0L) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { entry.progress }, modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Color(0xFFB7F36B), trackColor = Color(0xFF393C43)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("▶  Play / resume", color = Color(0xFFB7F36B), fontSize = 12.sp)
                }
                TextButton(onClick = { store.remove(entry.id); entries = store.all() }) { Text("Remove") }
            }
        }
    }
    if (showClearDialog) AlertDialog(
        onDismissRequest = { showClearDialog = false },
        title = { Text("Clear watch history?") },
        text = { Text("This removes all saved movie and series viewing progress from this device.") },
        confirmButton = {
            TextButton(onClick = { store.clear(); entries = emptyList(); showClearDialog = false }) { Text("Clear history") }
        },
        dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
    )
}

private fun formatWatchTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
