package com.nexvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun OfflineLibraryScreen(
    onPlay: (TvChannel) -> Unit,
    onTitleSelected: (TmdbTitle) -> Unit,
    profileId: String = NexvioProfileStore.active(LocalContext.current).id,
    onPlayQueue: ((List<TvChannel>, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val watchlist = remember(context, profileId) { WatchlistStore(context, profileId) }
    var saved by remember(context, profileId) { mutableStateOf(watchlist.all()) }
    var showDownloads by remember { mutableStateOf(false) }
    var cloudSelected by remember { mutableStateOf(false) }
    var gridMode by remember { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortByRecent by remember { mutableStateOf(true) }

    LaunchedEffect(watchlist, profileId) {
        while (true) {
            saved = watchlist.all()
            delay(1500L)
        }
    }

    if (showDownloads) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0D0D0F))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹", color = Color.White, fontSize = 32.sp,
                    modifier = Modifier.clickable { showDownloads = false }.padding(end = 14.dp)
                )
                Text("Downloads", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            DownloadsScreen(onPlay = onPlay, profileId = profileId, onPlayQueue = onPlayQueue)
        }
        return
    }

    val titles = if (sortByRecent) saved.reversed() else saved.sortedBy { it.title.lowercase() }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0D0F))) {
        // Keep the original Library header and its grid/list and Downloads controls.
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Library", modifier = Modifier.weight(1f), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (gridMode) "▦" else "☰", color = Color(0xFF9A9DA6), fontSize = 28.sp,
                modifier = Modifier.clickable { gridMode = !gridMode }.padding(horizontal = 12.dp)
            )
            Text(
                "⇩", color = Color(0xFF9A9DA6), fontSize = 30.sp,
                modifier = Modifier.clickable { showDownloads = true }.padding(start = 12.dp, end = 4.dp)
            )
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.background(if (!cloudSelected) Color(0xFF303034) else Color(0xFF19191D), RoundedCornerShape(28.dp))
                    .clickable { cloudSelected = false }.padding(horizontal = 22.dp, vertical = 10.dp)
            ) { Text("Saved", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.background(if (cloudSelected) Color(0xFF303034) else Color(0xFF19191D), RoundedCornerShape(28.dp))
                    .clickable { cloudSelected = true }.padding(horizontal = 22.dp, vertical = 10.dp)
            ) { Text("Cloud", color = Color(0xFF9A9DA6), fontSize = 14.sp) }
        }
        if (cloudSelected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Cloud sync isn't connected yet.", color = Color(0xFF9A9DA6))
            }
        } else {
            Box(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 20.dp)) {
                Row(
                    Modifier.background(Color(0xFF19191D), RoundedCornerShape(14.dp))
                        .clickable { sortMenu = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (sortByRecent) "Recently added" else "Name", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(" ⌄", color = Color(0xFF9A9DA6), fontSize = 18.sp)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    DropdownMenuItem(text = { Text("Recently added") }, onClick = { sortByRecent = true; sortMenu = false })
                    DropdownMenuItem(text = { Text("Name") }, onClick = { sortByRecent = false; sortMenu = false })
                }
            }
            if (titles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your watchlist is empty", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Add movies and series from their detail pages.", color = Color(0xFF9A9DA6))
                    }
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(titles, key = { it.mediaType + "-" + it.id }) { item ->
                        Column(Modifier.fillMaxWidth().clickable { onTitleSelected(item) }) {
                            AsyncImage(
                                model = item.posterPath?.let { "https://image.tmdb.org/t/p/w500" + it },
                                contentDescription = item.title,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(248.dp)
                                    .background(Color(0xFF19191D), RoundedCornerShape(12.dp))
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text(
                                (if (item.mediaType == "movie") "Movie" else "Series") + if (item.year.isNotBlank()) " • " + item.year else "",
                                color = Color(0xFF9A9DA6), fontSize = 12.sp
                            )
                            Text(
                                "▣", color = Color(0xFFCF9DA4), fontSize = 20.sp,
                                modifier = Modifier.clickable { watchlist.remove(item); saved = watchlist.all() }.padding(top = 5.dp)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(titles, key = { it.mediaType + "-" + it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFF19191D), RoundedCornerShape(14.dp))
                                .clickable { onTitleSelected(item) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = item.posterPath?.let { "https://image.tmdb.org/t/p/w500" + it },
                                contentDescription = item.title,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(width = 68.dp, height = 96.dp)
                                    .background(Color(0xFF26262A), RoundedCornerShape(8.dp))
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(if (item.mediaType == "movie") "Movie" else "Series", color = Color(0xFF9A9DA6), fontSize = 12.sp)
                            }
                            Text(
                                "▣", color = Color(0xFFCF9DA4),
                                modifier = Modifier.clickable { watchlist.remove(item); saved = watchlist.all() }
                            )
                        }
                    }
                }
            }
        }
    }
}
