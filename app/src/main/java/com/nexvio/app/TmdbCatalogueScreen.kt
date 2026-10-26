package com.nexvio.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TMDB_API_KEY = "df35ad7483eaf39d05d6b534109453bb"
private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
private val CatalogueInk = Color(0xFF101114)
private val CataloguePanel = Color(0xFF1B1D22)
private val CatalogueAccent = Color(0xFF8B7CFF)
private val CatalogueMuted = Color(0xFF9A9DA6)

internal data class TmdbTitle(
    val id: Int,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val year: String,
    val rating: Double,
    val mediaType: String
)

internal class WatchlistStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexvio_watchlist", Context.MODE_PRIVATE)
    fun all(): List<TmdbTitle> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            TmdbTitle(obj.optInt("id"), obj.optString("title"), obj.optString("overview"), obj.optString("posterPath").takeIf { it.isNotBlank() && it != "null" }, obj.optString("year"), obj.optDouble("rating"), obj.optString("mediaType"))
        }
    }
    fun contains(item: TmdbTitle): Boolean = all().any { it.id == item.id && it.mediaType == item.mediaType }
    fun add(item: TmdbTitle) { save(all().filterNot { it.id == item.id && it.mediaType == item.mediaType } + item) }
    fun remove(item: TmdbTitle) { save(all().filterNot { it.id == item.id && it.mediaType == item.mediaType }) }
    private fun save(items: List<TmdbTitle>) {
        val array = JSONArray()
        items.forEach { title -> array.put(JSONObject().put("id", title.id).put("title", title.title).put("overview", title.overview).put("posterPath", title.posterPath).put("year", title.year).put("rating", title.rating).put("mediaType", title.mediaType)) }
        prefs.edit().putString("items", array.toString()).apply()
    }
}

internal suspend fun fetchTmdbTitles(mediaType: String, query: String): List<TmdbTitle> = withContext(Dispatchers.IO) {
    require(TMDB_API_KEY != "PASTE_YOUR_TMDB_API_KEY_HERE" && TMDB_API_KEY.isNotBlank()) {
        "Add your TMDB API key in TmdbCatalogueScreen.kt before building the app."
    }
    val endpoint = if (query.isBlank()) {
        "https://api.themoviedb.org/3/trending/${if (mediaType == "multi") "all" else mediaType}/week"
    } else {
        val searchEndpoint = if (mediaType == "multi") "multi" else mediaType
        "https://api.themoviedb.org/3/search/$searchEndpoint?query=${URLEncoder.encode(query, "UTF-8")}&include_adult=false"
    }
    val separator = if (endpoint.contains("?")) "&" else "?"
    val connection = URL("$endpoint${separator}api_key=${URLEncoder.encode(TMDB_API_KEY, "UTF-8")}")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("TMDB returned HTTP $code. Check the API key and internet connection.")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id", 0)
            if (id == 0) return@mapNotNull null
            val resultType = if (mediaType == "multi") item.optString("media_type") else mediaType
            if (resultType != "movie" && resultType != "tv") return@mapNotNull null
            val rawTitle = if (resultType == "movie") item.optString("title") else item.optString("name")
            TmdbTitle(
                id = id,
                title = rawTitle.ifBlank { "Untitled" },
                overview = item.optString("overview"),
                posterPath = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
                year = (if (resultType == "movie") item.optString("release_date") else item.optString("first_air_date")).take(4),
                rating = item.optDouble("vote_average", 0.0),
                mediaType = resultType
            )
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun TmdbCatalogueScreen(mediaType: String, onPlay: (TvChannel) -> Unit, onTitleSelected: (TmdbTitle) -> Unit) {
    var query by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<TmdbTitle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<TmdbTitle?>(null) }
    val scope = rememberCoroutineScope()

    fun load(search: String) {
        scope.launch {
            loading = true
            error = null
            try {
                titles = fetchTmdbTitles("multi", search)
            } catch (e: Exception) {
                error = e.message ?: "Could not load catalogue."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            load("")
        } else {
            delay(350)
            load(query.trim())
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    selected?.let { item ->
        TitleDetailPage(item = item, onBack = { selected = null }, onPlay = onPlay)
        return
    }

    Column(Modifier.fillMaxSize().background(CatalogueInk).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Search", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Find movies and series in one place", color = CatalogueMuted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().background(CataloguePanel, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⌕", color = CatalogueMuted, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search any movie or series…", color = CatalogueMuted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                Text("×", color = CatalogueMuted, fontSize = 22.sp, modifier = Modifier.clickable { query = "" }.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading && titles.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CatalogueAccent)
            }
            error != null -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(error.orEmpty(), color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { load(query.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = CatalogueAccent, contentColor = CatalogueInk)) { Text("Try again") }
            }
            titles.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No matching movies or series found.", color = CatalogueMuted)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(titles, key = { it.mediaType + "-" + it.id }) { item ->
                    Column(Modifier.fillMaxWidth().clickable { onTitleSelected(item) }) {
                        AsyncImage(
                            model = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" },
                            contentDescription = item.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(230.dp).background(CataloguePanel, RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        val kind = if (item.mediaType == "movie") "Movie" else "Series"
                        val details = listOfNotNull(item.year.takeIf { it.isNotBlank() }, if (item.rating > 0) "★ " + String.format("%.1f", item.rating) else null).joinToString("  •  ")
                        Text(if (details.isBlank()) kind else "$kind  •  $details", color = CatalogueMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TitleDetailPage(item: TmdbTitle, onBack: () -> Unit, onPlay: (TvChannel) -> Unit) {
    val context = LocalContext.current
    val watchlist = remember(context) { WatchlistStore(context) }
    var savedToWatchlist by remember(item.id, item.mediaType) { mutableStateOf(watchlist.contains(item)) }
    val year = item.year.ifBlank { "Unknown year" }
    val rating = if (item.rating > 0) String.format("%.1f", item.rating) else "N/A"
    Column(Modifier.fillMaxSize().background(CatalogueInk)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹  Back", color = CatalogueAccent, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 18.dp))
            Text("${if (item.mediaType == "movie") "Movie" else "Series"} details", color = Color.White,
                fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(330.dp).background(CataloguePanel), contentAlignment = Alignment.Center) {
                    AsyncImage(model = item.posterPath?.let { "${TMDB_IMAGE_BASE}$it" }, contentDescription = item.title, modifier = Modifier.fillMaxSize())
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Text(item.title, color = Color.White, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(year, color = CatalogueMuted, fontSize = 14.sp)
                        Text("•", color = CatalogueMuted)
                        Text("★ $rating / 10", color = CatalogueAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("•", color = CatalogueMuted)
                        Text(if (item.mediaType == "movie") "Movie" else "Series", color = CatalogueMuted, fontSize = 14.sp)
                    }
                    Button(onClick = { if (savedToWatchlist) watchlist.remove(item) else watchlist.add(item); savedToWatchlist = !savedToWatchlist }, colors = ButtonDefaults.buttonColors(containerColor = CataloguePanel, contentColor = CatalogueAccent)) { Text(if (savedToWatchlist) "✓  In watchlist" else "＋  Add to watchlist") }
                    Spacer(Modifier.height(24.dp))
                    Text("Overview", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(item.overview.ifBlank { "No description is available for this title yet." }, color = CatalogueMuted, fontSize = 15.sp, lineHeight = 23.sp)
                    Spacer(Modifier.height(24.dp))
                    var selectedSeason by remember(item.id) { mutableIntStateOf(1) }
                    var selectedEpisode by remember(item.id) { mutableIntStateOf(1) }
                    if (item.mediaType == "tv") {
                        SeriesEpisodePicker(item = item, onSelection = { season, episode ->
                            selectedSeason = season
                            selectedEpisode = episode
                        })
                        Spacer(Modifier.height(14.dp))
                    }
                    StreamDiscoverySection(item = item, seasonNumber = selectedSeason, episodeNumber = selectedEpisode, onPlay = onPlay)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}


private data class DiscoveredStream(
    val addonName: String,
    val title: String,
    val url: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap()
)

private data class TmdbSeason(val number: Int, val name: String, val episodeCount: Int)
private data class TmdbEpisode(val number: Int, val name: String)

private suspend fun fetchTmdbSeasons(item: TmdbTitle): List<TmdbSeason> = withContext(Dispatchers.IO) {
    if (item.mediaType != "tv") return@withContext emptyList()
    val connection = URL("https://api.themoviedb.org/3/tv/${item.id}?api_key=${URLEncoder.encode(TMDB_API_KEY, "UTF-8")}")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        if (connection.responseCode !in 200..299) throw IllegalStateException("Could not load seasons from TMDB.")
        val seasons = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("seasons")
            ?: return@withContext emptyList()
        (0 until seasons.length()).mapNotNull { i ->
            val season = seasons.optJSONObject(i) ?: return@mapNotNull null
            val number = season.optInt("season_number", -1)
            if (number < 0 || season.optInt("episode_count", 0) <= 0) null
            else TmdbSeason(number, season.optString("name").ifBlank { "Season $number" }, season.optInt("episode_count"))
        }
    } finally { connection.disconnect() }
}

private suspend fun fetchTmdbEpisodes(item: TmdbTitle, seasonNumber: Int): List<TmdbEpisode> = withContext(Dispatchers.IO) {
    val connection = URL("https://api.themoviedb.org/3/tv/${item.id}/season/$seasonNumber?api_key=${URLEncoder.encode(TMDB_API_KEY, "UTF-8")}")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        if (connection.responseCode !in 200..299) throw IllegalStateException("Could not load episodes from TMDB.")
        val episodes = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("episodes")
            ?: return@withContext emptyList()
        (0 until episodes.length()).mapNotNull { i ->
            val episode = episodes.optJSONObject(i) ?: return@mapNotNull null
            val number = episode.optInt("episode_number", 0)
            if (number <= 0) null else TmdbEpisode(number, episode.optString("name").ifBlank { "Episode $number" })
        }
    } finally { connection.disconnect() }
}

@Composable
private fun SeriesEpisodePicker(item: TmdbTitle, onSelection: (Int, Int) -> Unit) {
    var seasons by remember(item.id) { mutableStateOf<List<TmdbSeason>>(emptyList()) }
    var episodes by remember(item.id) { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var selectedSeason by remember(item.id) { mutableIntStateOf(1) }
    var selectedEpisode by remember(item.id) { mutableIntStateOf(1) }
    var seasonMenu by remember { mutableStateOf(false) }
    var episodeMenu by remember { mutableStateOf(false) }
    var loadingEpisodes by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        try {
            seasons = fetchTmdbSeasons(item)
            selectedSeason = seasons.firstOrNull { it.number == 1 }?.number ?: seasons.firstOrNull()?.number ?: 1
        } catch (e: Exception) { pickerError = e.message ?: "Could not load seasons." }
    }
    LaunchedEffect(item.id, selectedSeason) {
        loadingEpisodes = true
        try {
            episodes = fetchTmdbEpisodes(item, selectedSeason)
            selectedEpisode = episodes.firstOrNull()?.number ?: 1
            onSelection(selectedSeason, selectedEpisode)
            pickerError = if (episodes.isEmpty()) "No episode list is available for this season." else null
        } catch (e: Exception) {
            episodes = emptyList()
            pickerError = e.message ?: "Could not load episodes."
        } finally { loadingEpisodes = false }
    }

    Column(Modifier.fillMaxWidth().background(CataloguePanel, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("Choose episode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { seasonMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(seasons.firstOrNull { it.number == selectedSeason }?.name ?: "Season $selectedSeason", maxLines = 1)
                }
                DropdownMenu(expanded = seasonMenu, onDismissRequest = { seasonMenu = false }) {
                    seasons.forEach { season ->
                        DropdownMenuItem(text = { Text(season.name) }, onClick = {
                            seasonMenu = false
                            if (selectedSeason != season.number) selectedSeason = season.number
                        })
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { episodeMenu = true }, enabled = episodes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(episodes.firstOrNull { it.number == selectedEpisode }?.let { "E${it.number}: ${it.name}" } ?: "Episode $selectedEpisode", maxLines = 1)
                }
                DropdownMenu(expanded = episodeMenu, onDismissRequest = { episodeMenu = false }) {
                    episodes.forEach { episode ->
                        DropdownMenuItem(text = { Text("E${episode.number}: ${episode.name}") }, onClick = {
                            episodeMenu = false
                            selectedEpisode = episode.number
                            onSelection(selectedSeason, selectedEpisode)
                        })
                    }
                }
            }
        }
        if (loadingEpisodes) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CatalogueAccent)
        if (pickerError != null) Text(pickerError.orEmpty(), color = CatalogueMuted, fontSize = 11.sp)
    }
}

private suspend fun fetchTmdbImdbId(item: TmdbTitle): String = withContext(Dispatchers.IO) {
    val typePath = if (item.mediaType == "movie") "movie" else "tv"
    val connection = URL("https://api.themoviedb.org/3/$typePath/${item.id}/external_ids?api_key=${URLEncoder.encode(TMDB_API_KEY, "UTF-8")}")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        val status = connection.responseCode
        if (status !in 200..299) throw IllegalStateException("TMDB external ID lookup failed (HTTP $status).")
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("imdb_id")
            .takeIf { it.startsWith("tt") }
            ?: throw IllegalStateException("TMDB has no IMDb ID for this title, so compatible add-ons cannot search it.")
    } finally { connection.disconnect() }
}

private suspend fun fetchAddonStreams(context: android.content.Context, item: TmdbTitle, seasonNumber: Int, episodeNumber: Int): List<DiscoveredStream> = withContext(Dispatchers.IO) {
    val output = mutableListOf<DiscoveredStream>()

    // Nuvio plugin repositories are independent of Stremio add-ons. Always try them,
    // even when the Stremio add-on list is empty or its IMDb lookup fails.
    try {
        NuvioScraperEngine.discover(context, item, seasonNumber, episodeNumber).forEach { stream ->
            output.add(DiscoveredStream(stream.providerName, stream.title, stream.url, stream.quality, stream.headers))
        }
    } catch (_: Exception) {
        // Keep trying Stremio add-ons even if the Nuvio adapter is unavailable.
    }

    val prefs = context.getSharedPreferences("nexvio_addons", android.content.Context.MODE_PRIVATE)
    val installed = org.json.JSONArray(prefs.getString("installed", "[]") ?: "[]")
    val addons = (0 until installed.length()).mapNotNull { installed.optJSONObject(it) }
        .filter { it.optBoolean("enabled", true) }
        .mapNotNull { obj ->
            val name = obj.optString("name").ifBlank { "Add-on" }
            val manifest = obj.optString("manifestUrl").trim()
            if (manifest.startsWith("https://") || manifest.startsWith("http://")) name to manifest else null
        }

    // No enabled Stremio add-ons is valid when plugin repositories are configured.
    if (addons.isNotEmpty()) {
        val imdbId = try { fetchTmdbImdbId(item) } catch (_: Exception) { null }
        if (imdbId != null) {
            val resourceType = if (item.mediaType == "movie") "movie" else "series"
            val streamId = if (item.mediaType == "movie") imdbId else "$imdbId:$seasonNumber:$episodeNumber"
            for ((addonName, manifestUrl) in addons) {
                try {
                    val manifestUri = java.net.URI(manifestUrl)
                    val path = manifestUri.path.orEmpty().removeSuffix("/").removeSuffix("manifest.json").trimEnd('/')
                    val base = "${manifestUri.scheme}://${manifestUri.rawAuthority}$path"
                    val streamUrl = "$base/stream/$resourceType/$streamId.json"
                    val connection = URL(streamUrl).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 7000
                        connection.readTimeout = 9000
                        connection.instanceFollowRedirects = true
                        connection.setRequestProperty("User-Agent", "Nexvio/1.0 Android")
                        connection.setRequestProperty("Accept", "application/json")
                        if (connection.responseCode !in 200..299) continue
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val streams = json.optJSONArray("streams") ?: continue
                        for (i in 0 until streams.length()) {
                            val stream = streams.optJSONObject(i) ?: continue
                            val url = stream.optString("url").trim()
                            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                            val label = stream.optString("title").ifBlank { stream.optString("name") }.ifBlank { addonName }
                            val quality = Regex("(?i)(2160p|1080p|720p|480p|360p|4k)").find("$label ${stream.optString("name")}")?.value?.uppercase() ?: "Quality unknown"
                            val headersJson = stream.optJSONObject("headers")
                            val headers = headersJson?.let { obj -> buildMap { val keys = obj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, obj.optString(key)) } } }.orEmpty()
                            output.add(DiscoveredStream(addonName, label, url, quality, headers))
                        }
                    } finally { connection.disconnect() }
                } catch (_: Exception) {
                    // One unavailable add-on should not block results from other providers.
                }
            }
        }
    }
    output.distinctBy { it.url }.take(50)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamDiscoverySection(item: TmdbTitle, seasonNumber: Int, episodeNumber: Int, onPlay: (TvChannel) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var streams by remember(item.id, item.mediaType) { mutableStateOf<List<DiscoveredStream>>(emptyList()) }
    var loading by remember(item.id, item.mediaType) { mutableStateOf(false) }
    var searched by remember(item.id, item.mediaType) { mutableStateOf(false) }
    var error by remember(item.id, item.mediaType) { mutableStateOf<String?>(null) }
    var qualityFilter by remember(item.id, item.mediaType) { mutableStateOf("All qualities") }
    var qualityMenu by remember { mutableStateOf(false) }
    var downloadTarget by remember { mutableStateOf<DiscoveredStream?>(null) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }

    downloadTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { downloadTarget = null },
            title = { Text("Download for offline") },
            text = { Text("Save " + item.title + (if (item.mediaType == "series") " • S" + seasonNumber + "E" + episodeNumber else "") + " from " + target.addonName + " (" + target.quality + ")? Only direct media files are supported. HLS/DASH playlists, DRM and provider pages cannot be converted by this downloader.") },
            confirmButton = {
                TextButton(onClick = {
                    val title = item.title + (if (item.mediaType == "series") " S" + seasonNumber + "E" + episodeNumber else "") + " - " + target.quality + " - " + target.addonName
                    try {
                        enqueueDiscoveredStream(context, title, target.url)
                        downloadMessage = "Download queued. Track it in Library → Offline."
                    } catch (e: Exception) {
                        downloadMessage = e.message ?: "This stream cannot be downloaded as a direct media file."
                    }
                    downloadTarget = null
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { downloadTarget = null }) { Text("Cancel") } }
        )
    }
    downloadMessage?.let { message ->
        AlertDialog(onDismissRequest = { downloadMessage = null }, title = { Text("Nexvio downloads") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { downloadMessage = null }) { Text("OK") } })
    }

    Column(Modifier.fillMaxWidth().background(CataloguePanel, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text("Where to watch", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (item.mediaType == "movie") "Search enabled Stremio add-ons and compatible Nuvio repository scrapers for playable sources."
            else "Search enabled Stremio add-ons and compatible Nuvio scrapers for season $seasonNumber, episode $episodeNumber.",
            color = CatalogueMuted, fontSize = 13.sp, lineHeight = 19.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    loading = true
                    searched = true
                    error = null
                    streams = emptyList()
                    try {
                        streams = fetchAddonStreams(context, item, seasonNumber, episodeNumber)
                        if (streams.isEmpty()) error = "No streams were returned. The installed provider may use an unsupported protocol, or this title may not be available."
                    } catch (e: Exception) {
                        error = e.message ?: "Stream discovery failed."
                    } finally { loading = false }
                }
            },
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(containerColor = CatalogueAccent, contentColor = CatalogueInk)
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = CatalogueInk, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (loading) "Finding streams…" else "Find streams")
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error.orEmpty(), color = CatalogueMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (streams.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("${streams.size} source(s) found", color = CatalogueAccent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { qualityMenu = true }) { Text("Quality: $qualityFilter") }
                DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                    listOf("All qualities", "4K", "2160P", "1080P", "720P", "480P", "360P", "Quality unknown").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { qualityFilter = option; qualityMenu = false })
                    }
                }
            }
            val visibleStreams = streams.filter { stream ->
                qualityFilter == "All qualities" ||
                    (qualityFilter == "Quality unknown" && stream.quality == "Quality unknown") ||
                    stream.quality.equals(qualityFilter, ignoreCase = true) ||
                    (qualityFilter == "4K" && stream.quality.equals("2160P", ignoreCase = true))
            }
            if (visibleStreams.isEmpty()) Text("No sources match this quality filter.", color = CatalogueMuted, fontSize = 12.sp)
            visibleStreams.forEach { stream ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().background(CatalogueInk, RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClick = {
                            onPlay(TvChannel(
                                id = "addon-${item.mediaType}-${item.id}-${stream.url.hashCode()}",
                                name = "${item.title} • ${stream.quality}",
                                streamUrl = stream.url,
                                logoUrl = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" },
                                category = stream.addonName,
                                tvgId = null,
                                tvgName = stream.title,
                                isFavorite = false,
                                requestHeaders = stream.headers
                            ))
                            },
                            onLongClick = { downloadTarget = stream }
                        ).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stream.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(stream.addonName, color = CatalogueMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stream.quality, color = CatalogueAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("▶", color = CatalogueAccent)
                }
            }
        } else if (searched && !loading && error == null) {
            Spacer(Modifier.height(8.dp))
            Text("No playable sources found.", color = CatalogueMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("Results combine Stremio add-ons with a restricted Nuvio JavaScript adapter. Some scrapers may require unsupported modules or host APIs.", color = CatalogueMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}
