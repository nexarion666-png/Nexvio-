package com.nexvio.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val LibraryBackground = Color(0xFF101114)
private val LibraryPanel = Color(0xFF1B1D22)
private val LibraryAccent = Color(0xFF8B7CFF)
private val LibraryMuted = Color(0xFF9A9DA6)

@Composable
internal fun OfflineLibraryScreen(
    onPlay: (TvChannel) -> Unit,
    onTitleSelected: (TmdbTitle) -> Unit,
    profileId: String = NexvioProfileStore.active(LocalContext.current).id
) {
    val context = LocalContext.current
    val watchlist = remember(context, profileId) { WatchlistStore(context, profileId) }
    var titles by remember(context, profileId) { mutableStateOf(watchlist.all()) }

    // Refresh while the Library is visible so changes made on a title details page
    // or restored by account sync are reflected without restarting the app.
    LaunchedEffect(watchlist, profileId) {
        while (true) {
            titles = watchlist.all()
            delay(1500L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LibraryBackground),
        contentPadding = PaddingValues(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Your Library", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
                Text("Your watchlist and saved titles", color = LibraryMuted, fontSize = 14.sp)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Watchlist", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("${titles.size}", color = LibraryAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (titles.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(LibraryPanel).padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("＋", color = LibraryAccent, fontSize = 32.sp)
                        Text("Your watchlist is empty", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Open a movie or series and tap “Add to watchlist” to save it here.",
                            color = LibraryMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            items(titles, key = { "${it.mediaType}-${it.id}" }) { title ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LibraryPanel)
                        .clickable { onTitleSelected(title) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = title.posterPath?.let { "https://image.tmdb.org/t/p/w185$it" },
                        contentDescription = title.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 82.dp, height = 116.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF292B32))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(title.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(
                                if (title.mediaType == "movie") "Movie" else "Series",
                                title.year.takeIf { it.isNotBlank() }
                            ).filterNotNull().joinToString(" • "),
                            color = LibraryMuted,
                            fontSize = 12.sp
                        )
                        if (title.rating > 0.0) {
                            Text("★ ${String.format("%.1f", title.rating)} / 10", color = LibraryAccent, fontSize = 12.sp)
                        }
                        Text("Tap to view details  ›", color = LibraryAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
