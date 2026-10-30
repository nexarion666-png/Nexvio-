package com.nexvio.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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

internal class WatchlistStore(context: Context, profileId: String = NexvioProfileStore.active(context).id) {
    private val prefs = context.getSharedPreferences(if (profileId == "primary") "nexvio_watchlist" else "nexvio_watchlist_$profileId", Context.MODE_PRIVATE)
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

internal object CustomCollectionStore {
    private fun prefs(context: Context) = context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE)

    fun names(context: Context): List<String> {
        val array = runCatching { JSONArray(prefs(context).getString("layout_collections", "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    fun titles(context: Context, name: String): List<TmdbTitle> {
        val root = runCatching { JSONObject(prefs(context).getString("layout_collection_items", "{}")) }.getOrElse { JSONObject() }
        val array = root.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optInt("id", 0)
            if (id <= 0) return@mapNotNull null
            TmdbTitle(
                id = id,
                title = obj.optString("title", "Untitled"),
                overview = obj.optString("overview"),
                posterPath = obj.optString("posterPath").takeIf { it.isNotBlank() && it != "null" },
                year = obj.optString("year"),
                rating = obj.optDouble("rating", 0.0),
                mediaType = obj.optString("mediaType", "movie")
            )
        }
    }

    fun add(context: Context, name: String, title: TmdbTitle) {
        val settings = prefs(context)
        val root = runCatching { JSONObject(settings.getString("layout_collection_items", "{}")) }.getOrElse { JSONObject() }
        val old = root.optJSONArray(name) ?: JSONArray()
        val titles = (0 until old.length()).mapNotNull { old.optJSONObject(it) }
            .filterNot { it.optInt("id") == title.id && it.optString("mediaType") == title.mediaType }
        val array = JSONArray()
        titles.forEach { array.put(it) }
        array.put(JSONObject()
            .put("id", title.id)
            .put("title", title.title)
            .put("overview", title.overview)
            .put("posterPath", title.posterPath)
            .put("year", title.year)
            .put("rating", title.rating)
            .put("mediaType", title.mediaType))
        root.put(name, array)
        settings.edit().putString("layout_collection_items", root.toString()).apply()
    }

    fun removeCollection(context: Context, name: String) {
        val settings = prefs(context)
        val root = runCatching { JSONObject(settings.getString("layout_collection_items", "{}")) }.getOrElse { JSONObject() }
        root.remove(name)
        settings.edit().putString("layout_collection_items", root.toString()).apply()
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
internal fun TmdbCatalogueScreen(mediaType: String, onPlay: (TvChannel) -> Unit, onTitleSelected: (TmdbTitle) -> Unit, profileId: String = NexvioProfileStore.active(LocalContext.current).id) {
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
        TitleDetailPage(item = item, onBack = { selected = null }, onPlay = onPlay, profileId = profileId)
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
internal fun TitleDetailPage(item: TmdbTitle, onBack: () -> Unit, onPlay: (TvChannel) -> Unit, onFindStreams: (TmdbTitle, Int, Int) -> Unit = { _, _, _ -> }, profileId: String = NexvioProfileStore.active(LocalContext.current).id) {
    val context = LocalContext.current
    val detailPrefs = remember(context) { context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE) }
    val showDetailPoster = remember(item.id, item.mediaType, profileId) { detailPrefs.getBoolean("detail_show_poster", true) }
    val showDetailMetadata = remember(item.id, item.mediaType, profileId) { detailPrefs.getBoolean("detail_show_metadata", true) }
    val showDetailOverview = remember(item.id, item.mediaType, profileId) { detailPrefs.getBoolean("detail_show_overview", true) }
    val showDetailWatchlist = remember(item.id, item.mediaType, profileId) { detailPrefs.getBoolean("detail_show_watchlist", true) }
    val showDetailEpisodes = remember(item.id, item.mediaType, profileId) { detailPrefs.getBoolean("detail_show_episodes", true) }
    val watchlist = remember(context, profileId) { WatchlistStore(context, profileId) }
    var savedToWatchlist by remember(item.id, item.mediaType) { mutableStateOf(watchlist.contains(item)) }
    var collectionDialog by remember(item.id, item.mediaType) { mutableStateOf(false) }
    var collectionNames by remember(item.id, item.mediaType) { mutableStateOf(CustomCollectionStore.names(context)) }
    var collectionMessage by remember(item.id, item.mediaType) { mutableStateOf<String?>(null) }
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
            if (showDetailPoster) {
                item {
                    Box(Modifier.fillMaxWidth().height(330.dp).background(CataloguePanel), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = item.posterPath?.let { "${TMDB_IMAGE_BASE}$it" },
                            contentDescription = item.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Text(item.title, color = Color.White, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    if (showDetailMetadata) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(year, color = CatalogueMuted, fontSize = 14.sp)
                            Text("•", color = CatalogueMuted)
                            Text("★ $rating / 10", color = CatalogueAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("•", color = CatalogueMuted)
                            Text(if (item.mediaType == "movie") "Movie" else "Series", color = CatalogueMuted, fontSize = 14.sp)
                        }
                    }
                    if (showDetailWatchlist) {
                        Button(
                            onClick = { if (savedToWatchlist) watchlist.remove(item) else watchlist.add(item); savedToWatchlist = !savedToWatchlist },
                            colors = ButtonDefaults.buttonColors(containerColor = CataloguePanel, contentColor = CatalogueAccent)
                        ) { Text(if (savedToWatchlist) "✓  In watchlist" else "＋  Add to watchlist") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            collectionNames = CustomCollectionStore.names(context)
                            collectionDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CatalogueAccent)
                    ) { Text("＋  Add to collection") }
                    collectionMessage?.let { Text(it, color = CatalogueAccent, fontSize = 12.sp) }
                    if (collectionDialog) {
                        AlertDialog(
                            onDismissRequest = { collectionDialog = false },
                            title = { Text("Add to collection") },
                            text = {
                                if (collectionNames.isEmpty()) {
                                    Text("You have no custom collections yet. Create one in Settings → Layout → Collections.")
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        collectionNames.forEach { collectionName ->
                                            TextButton(
                                                onClick = {
                                                    CustomCollectionStore.add(context, collectionName, item)
                                                    collectionMessage = "Added to $collectionName"
                                                    collectionDialog = false
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text(collectionName, modifier = Modifier.fillMaxWidth()) }
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { collectionDialog = false }) { Text("Done") } }
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    if (showDetailOverview) {
                        Text("Overview", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(item.overview.ifBlank { "No description is available for this title yet." }, color = CatalogueMuted, fontSize = 15.sp, lineHeight = 23.sp)
                        Spacer(Modifier.height(24.dp))
                    }
                    var selectedSeason by remember(item.id) { mutableIntStateOf(1) }
                    var selectedEpisode by remember(item.id) { mutableIntStateOf(1) }
                    if (item.mediaType == "tv" && showDetailEpisodes) {
                        SeriesEpisodePicker(item = item, onSelection = { season, episode ->
                            selectedSeason = season
                            selectedEpisode = episode
                        })
                        Spacer(Modifier.height(14.dp))
                    }
                    Button(onClick = { onFindStreams(item, selectedSeason, selectedEpisode) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CatalogueAccent, contentColor = CatalogueInk)) { Text("▶  Find streams") }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}


@Composable
internal fun StreamSearchPage(item: TmdbTitle, seasonNumber: Int, episodeNumber: Int, onBack: () -> Unit, onPlay: (TvChannel) -> Unit) {
    Column(Modifier.fillMaxSize().background(CatalogueInk)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹  Back", color = CatalogueAccent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack).padding(end = 18.dp))
            Column(Modifier.weight(1f)) {
                Text("Find streams", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(item.title, color = CatalogueMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item { StreamDiscoverySection(item, seasonNumber, episodeNumber, onPlay, autoStart = true, fullPage = true) }
        }
    }
}

private data class DiscoveredStream(
    val addonName: String,
    val title: String,
    val url: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
    val infoHash: String? = null,
    val fileIdx: Int? = null
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

private suspend fun fetchAddonStreams(context: android.content.Context, item: TmdbTitle, seasonNumber: Int, episodeNumber: Int, onStreams: suspend (List<DiscoveredStream>) -> Unit, onProviderStatus: suspend (String, String) -> Unit): List<DiscoveredStream> = withContext(Dispatchers.IO) {
    val output = java.util.Collections.synchronizedList(mutableListOf<DiscoveredStream>())
    val publishLock = kotlinx.coroutines.sync.Mutex()

    suspend fun publish(newStreams: List<DiscoveredStream>) {
        if (newStreams.isEmpty()) return
        publishLock.withLock {
            val snapshot = synchronized(output) {
                output.addAll(newStreams)
                output.distinctBy(::streamDedupeKey)
            }
            onStreams(snapshot)
        }
    }

    // Start Nuvio providers immediately, but do not wait for all of them before
    // asking Stremio add-ons. Both sources can publish into the same live list.
    kotlinx.coroutines.coroutineScope {
        val nuvioJob = async {
            try {
                NuvioScraperEngine.discover(context, item, seasonNumber, episodeNumber, onStreams = { found ->
                    publish(found.map { stream ->
                        DiscoveredStream(stream.providerName, stream.title, stream.url, stream.quality, stream.headers)
                    })
                }, onStatus = { message ->
                    val separator = message.indexOf(":")
                    val provider = if (separator > 0) message.substring(0, separator).trim() else "Nuvio provider"
                    val status = if (separator > 0) message.substring(separator + 1).trim() else message
                    onProviderStatus(provider, status)
                })
            } catch (_: Exception) {
                // Stremio add-ons can still return results if Nuvio providers fail.
            }
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

        if (addons.isNotEmpty()) {
            val imdbId = try { fetchTmdbImdbId(item) } catch (_: Exception) { null }
            if (imdbId != null) {
                val resourceType = if (item.mediaType == "movie") "movie" else "series"
                val streamId = if (item.mediaType == "movie") imdbId else "$imdbId:$seasonNumber:$episodeNumber"
                // Query all enabled add-ons concurrently so a slow source cannot
                // prevent faster sources from publishing results.
                val addonJobs = addons.map { (addonName, manifestUrl) ->
                    async(Dispatchers.IO) {
                        onProviderStatus(addonName, "Requesting streams")
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
                                val code = connection.responseCode
                                if (code !in 200..299) {
                                    onProviderStatus(addonName, "Failed — HTTP $code")
                                    android.util.Log.w("NexvioStreams", "$addonName returned HTTP $code")
                                    return@async
                                }
                                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                                val trimmedBody = responseBody.trimStart()
                                if (trimmedBody.startsWith("<!DOCTYPE", ignoreCase = true) || trimmedBody.startsWith("<html", ignoreCase = true)) {
                                    onProviderStatus(addonName, "Failed — server returned HTML, not stream JSON")
                                    android.util.Log.w("NexvioStreams", "$addonName returned HTML instead of a Stremio stream response")
                                    return@async
                                }
                                val json = try { JSONObject(responseBody) } catch (_: Exception) {
                                    onProviderStatus(addonName, "Failed — invalid JSON response")
                                    android.util.Log.w("NexvioStreams", "$addonName returned invalid JSON")
                                    return@async
                                }
                                val streams = json.optJSONArray("streams") ?: run {
                                    onProviderStatus(addonName, "Failed — response has no streams array")
                                    android.util.Log.w("NexvioStreams", "$addonName response has no streams array")
                                    return@async
                                }
                                val found = (0 until streams.length()).mapNotNull { i ->
                                    val stream = streams.optJSONObject(i) ?: return@mapNotNull null
                                    val url = stream.optString("url").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
                                    val infoHash = stream.optString("infoHash").trim().takeIf { it.matches(Regex("(?i)^[a-f0-9]{40}$")) }
                                    // Torrentio commonly returns infoHash/fileIdx instead of a direct URL.
                                    // Keep those results visible rather than silently discarding them.
                                    if (url == null && infoHash == null) return@mapNotNull null
                                    val label = stream.optString("title").ifBlank { stream.optString("name") }.ifBlank { addonName }
                                    val quality = Regex("(?i)(2160p|1080p|720p|480p|360p|4k)").find("$label ${stream.optString("name")}")?.value?.uppercase() ?: "Quality unknown"
                                    val headersJson = stream.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request") ?: stream.optJSONObject("headers")
                                    val headers = headersJson?.let { obj -> buildMap { val keys = obj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, obj.optString(key)) } } }.orEmpty()
                                    DiscoveredStream(addonName, label, url ?: "", quality, headers, infoHash, if (stream.has("fileIdx") && !stream.isNull("fileIdx")) stream.optInt("fileIdx") else null)
                                }
                                publish(found)
                                onProviderStatus(addonName, if (found.isEmpty()) "Completed — no streams" else "Completed — ${found.size} stream(s)")
                                android.util.Log.i("NexvioStreams", "$addonName returned ${found.size} streams")
                            } finally { connection.disconnect() }
                        } catch (e: Exception) {
                            onProviderStatus(addonName, "Failed — ${e.message ?: "network or parsing error"}")
                            android.util.Log.w("NexvioStreams", "$addonName failed: ${e.message}")
                        }
                    }
                }
                addonJobs.forEach { job -> try { job.await() } catch (_: Exception) { } }
            }
        }
        nuvioJob.await()
    }
    synchronized(output) { output.distinctBy(::streamDedupeKey) }
}

private fun streamDedupeKey(stream: DiscoveredStream): String =
    listOf(stream.addonName, stream.url, stream.infoHash.orEmpty(), stream.fileIdx?.toString().orEmpty(), stream.title, stream.quality,
        stream.headers.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }).joinToString("\u0000")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamDiscoverySection(item: TmdbTitle, seasonNumber: Int, episodeNumber: Int, onPlay: (TvChannel) -> Unit, autoStart: Boolean = false, fullPage: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val streamPrefs = remember(context) { context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE) }
    val streamSortOrder = remember(item.id, item.mediaType) { streamPrefs.getString("stream_sort_order", "Recommended") ?: "Recommended" }
    val compactStreamRows = remember(item.id, item.mediaType) { streamPrefs.getBoolean("stream_compact_rows", false) }
    val showStreamProvider = remember(item.id, item.mediaType) { streamPrefs.getBoolean("stream_show_provider", true) }
    val showStreamQuality = remember(item.id, item.mediaType) { streamPrefs.getBoolean("stream_show_quality", true) }
    var streams by remember(item.id, item.mediaType) { mutableStateOf<List<DiscoveredStream>>(emptyList()) }
    val addonNames = remember(context) {
        val installed = runCatching { org.json.JSONArray(context.getSharedPreferences("nexvio_addons", Context.MODE_PRIVATE).getString("installed", "[]") ?: "[]") }.getOrElse { org.json.JSONArray() }
        ((0 until installed.length()).mapNotNull { installed.optJSONObject(it) }
            .filter { it.optBoolean("enabled", true) }
            .map { it.optString("name").ifBlank { "Add-on" } } + "Vidlink").distinct()
    }
    val providerStatuses = remember(item.id, item.mediaType) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { addonNames.forEach { put(it, "Waiting") } } }
    var loading by remember(item.id, item.mediaType) { mutableStateOf(false) }
    var searched by remember(item.id, item.mediaType) { mutableStateOf(false) }
    var error by remember(item.id, item.mediaType) { mutableStateOf<String?>(null) }
    var qualityFilter by remember(item.id, item.mediaType) { mutableStateOf("All qualities") }
    var selectedProvider by remember(item.id, item.mediaType) { mutableStateOf("All") }
    var qualityMenu by remember { mutableStateOf(false) }
    var downloadTarget by remember { mutableStateOf<DiscoveredStream?>(null) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var loadingMessage by remember { mutableStateOf("Checking providers") }
    val loaderTransition = rememberInfiniteTransition(label = "stream-search-loader")
    val loaderRotation by loaderTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "stream-search-rotation"
    )
    LaunchedEffect(loading) {
        if (loading) {
            val messages = listOf("Checking providers", "Searching for playable links", "Waiting for source responses", "Collecting stream details")
            var index = 0
            while (true) {
                loadingMessage = messages[index % messages.size]
                index++
                delay(1500)
            }
        } else {
            loadingMessage = if (streams.isEmpty()) "Search complete" else "Streams found"
        }
    }

    fun startSearch() {
        if (loading) return
        scope.launch {
            loading = true; searched = true; error = null; streams = emptyList()
            try {
                streams = fetchAddonStreams(context, item, seasonNumber, episodeNumber, onStreams = { found ->
                    withContext(Dispatchers.Main) { streams = found; error = null }
                }, onProviderStatus = { provider, status ->
                    withContext(Dispatchers.Main) { providerStatuses[provider] = status }
                })
                if (streams.isEmpty()) error = "No streams were returned. The installed provider may use an unsupported protocol, or this title may not be available."
            } catch (e: Exception) { error = e.message ?: "Stream discovery failed." }
            finally { loading = false }
        }
    }
    LaunchedEffect(item.id, item.mediaType, seasonNumber, episodeNumber, autoStart) {
        if (autoStart && !searched) startSearch()
    }

    downloadTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { downloadTarget = null },
            title = { Text("Download for offline") },
            text = { Text("Save " + item.title + (if (item.mediaType == "series") " • S" + seasonNumber + "E" + episodeNumber else "") + " from " + target.addonName + " (" + target.quality + ")? Only direct media files are supported. HLS/DASH playlists, DRM and provider pages cannot be converted by this downloader.") },
            confirmButton = {
                TextButton(onClick = {
                    // Use the media title as the filename; series also include SxxExx.
                    val title = item.title + (if (item.mediaType == "series") " - S" + seasonNumber.toString().padStart(2, '0') + "E" + episodeNumber.toString().padStart(2, '0') else "")
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

    Column(if (fullPage) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().background(CataloguePanel, RoundedCornerShape(16.dp)).padding(16.dp)) {
        if (fullPage) {
            Box(Modifier.fillMaxWidth().height(260.dp).background(CataloguePanel)) {
                AsyncImage(model = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" }, contentDescription = item.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC101114)).padding(horizontal = 16.dp, vertical = 18.dp)) {
                    Text(if (item.mediaType == "movie") "MOVIE" else "S${seasonNumber} E${episodeNumber}", color = CatalogueAccent, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Text(item.title, color = Color.White, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (item.mediaType == "movie") "Movie" else "Season $seasonNumber • Episode $episodeNumber", color = CatalogueMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                addonNames.forEach { name ->
                    val status = providerStatuses[name] ?: "Waiting"
                    val isGood = status.startsWith("Completed", ignoreCase = true) && !status.contains("no streams", ignoreCase = true)
                    val isBad = status.startsWith("Failed", ignoreCase = true) || status.contains("network/download error", ignoreCase = true) || status.contains("runtime error", ignoreCase = true)
                    Column(Modifier.background(if (isGood) Color(0xFF183323) else CataloguePanel, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(status, color = when { isGood -> Color(0xFF7FE0A0); isBad -> Color(0xFFFF8A8A); else -> CatalogueMuted }, fontSize = 10.sp, maxLines = 1)
                    }
                }
                if (addonNames.isEmpty()) Text("No enabled add-ons", color = CatalogueMuted, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(18.dp))
        } else Text("Where to watch", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (item.mediaType == "movie") "Search enabled Stremio add-ons and compatible Nuvio repository scrapers for playable sources."
            else "Search enabled Stremio add-ons and compatible Nuvio scrapers for season $seasonNumber, episode $episodeNumber.",
            color = CatalogueMuted, fontSize = 13.sp, lineHeight = 19.sp
        )
        Spacer(Modifier.height(12.dp))
        if (!fullPage) {
            Button(onClick = { startSearch() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = CatalogueAccent, contentColor = CatalogueInk)) {
                if (loading) { CircularProgressIndicator(Modifier.size(18.dp), color = CatalogueInk, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (loading) "Finding streams…" else "Find streams")
            }
        }
        if (loading) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().background(CataloguePanel, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(25.dp).rotate(loaderRotation), color = CatalogueAccent, strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(loadingMessage, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("${streams.size} stream(s) found so far", color = CatalogueMuted, fontSize = 12.sp)
                }
                Text("LIVE", color = CatalogueAccent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error.orEmpty(), color = CatalogueMuted, fontSize = 12.sp, lineHeight = 17.sp)
            if (!loading && searched) {
                TextButton(onClick = { startSearch() }) { Text("Retry search", color = CatalogueAccent) }
            }
        }
        if (streams.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("${streams.size} source(s) found", color = CatalogueAccent, fontWeight = FontWeight.SemiBold)
            val providers = listOf("All") + streams.map { it.addonName }.distinct()
            ScrollableTabRow(selectedTabIndex = providers.indexOf(selectedProvider).coerceAtLeast(0), edgePadding = 0.dp, containerColor = CatalogueInk, contentColor = CatalogueAccent) {
                providers.forEach { provider ->
                    Tab(selected = selectedProvider == provider, onClick = { selectedProvider = provider }, text = { Text(provider, maxLines = 1) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { qualityMenu = true }) { Text("Quality: $qualityFilter") }
                DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                    listOf("All qualities", "4K", "2160P", "1080P", "720P", "480P", "360P", "Quality unknown").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { qualityFilter = option; qualityMenu = false })
                    }
                }
            }
            val filteredStreams = streams.filter { stream ->
                (selectedProvider == "All" || stream.addonName == selectedProvider) &&
                (qualityFilter == "All qualities" ||
                    (qualityFilter == "Quality unknown" && stream.quality == "Quality unknown") ||
                    stream.quality.equals(qualityFilter, ignoreCase = true) ||
                    (qualityFilter == "4K" && stream.quality.equals("2160P", ignoreCase = true)))
            }
            fun qualityRank(value: String): Int = when {
                value.contains("2160", true) || value.contains("4K", true) -> 2160
                value.contains("1080", true) -> 1080
                value.contains("720", true) -> 720
                value.contains("480", true) -> 480
                value.contains("360", true) -> 360
                else -> 0
            }
            val visibleStreams = when (streamSortOrder) {
                "Quality (high to low)" -> filteredStreams.sortedWith(compareByDescending<DiscoveredStream> { qualityRank(it.quality) }.thenBy { it.addonName.lowercase() })
                "Provider A–Z" -> filteredStreams.sortedWith(compareBy<DiscoveredStream> { it.addonName.lowercase() }.thenBy { it.title.lowercase() })
                else -> filteredStreams
            }
            if (visibleStreams.isEmpty()) Text("No sources match this quality filter.", color = CatalogueMuted, fontSize = 12.sp)
            visibleStreams.forEach { stream ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().background(CatalogueInk, RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClick = {
                            if (stream.infoHash != null) {
                                downloadMessage = "This add-on returned a torrent source (info hash), not a direct video URL. Nexvio currently lists it but cannot play torrent-only results until a torrent/debrid resolver is integrated."
                            } else onPlay(TvChannel(
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
                        ).padding(if (compactStreamRows) 8.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stream.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (compactStreamRows) 12.sp else 13.sp)
                        if (showStreamProvider) Text(stream.addonName, color = CatalogueMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (showStreamQuality) {
                        Text(stream.quality, color = CatalogueAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("▶", color = CatalogueAccent)
                }
            }
        } else if (searched && !loading && error == null) {
            Spacer(Modifier.height(8.dp))
            Text("No playable sources found.", color = CatalogueMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("Results from enabled add-ons and compatible providers appear here.", color = CatalogueMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}
