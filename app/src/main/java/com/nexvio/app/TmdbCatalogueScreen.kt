package com.nexvio.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TMDB_API_KEY = "df35ad7483eaf39d05d6b534109453bb"
private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
private val CatalogueInk = Color(0xFF101114)
private val CataloguePanel = Color(0xFF1B1D22)
private val CatalogueLime = Color(0xFFB7F36B)
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

private suspend fun fetchTmdbTitles(mediaType: String, query: String): List<TmdbTitle> = withContext(Dispatchers.IO) {
    require(TMDB_API_KEY != "PASTE_YOUR_TMDB_API_KEY_HERE" && TMDB_API_KEY.isNotBlank()) {
        "Add your TMDB API key in TmdbCatalogueScreen.kt before building the app."
    }
    val endpoint = if (query.isBlank()) {
        "https://api.themoviedb.org/3/trending/$mediaType/week"
    } else {
        "https://api.themoviedb.org/3/search/$mediaType?query=${URLEncoder.encode(query, "UTF-8")}&include_adult=false"
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
            val rawTitle = if (mediaType == "movie") item.optString("title") else item.optString("name")
            TmdbTitle(
                id = id,
                title = rawTitle.ifBlank { "Untitled" },
                overview = item.optString("overview"),
                posterPath = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
                year = (if (mediaType == "movie") item.optString("release_date") else item.optString("first_air_date")).take(4),
                rating = item.optDouble("vote_average", 0.0),
                mediaType = mediaType
            )
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun TmdbCatalogueScreen(mediaType: String, onPlay: (TvChannel) -> Unit) {
    var query by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<TmdbTitle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<TmdbTitle?>(null) }
    val scope = rememberCoroutineScope()
    val heading = if (mediaType == "movie") "Movies" else "Series"

    fun load(search: String) {
        scope.launch {
            loading = true
            error = null
            try {
                titles = fetchTmdbTitles(mediaType, search)
            } catch (e: Exception) {
                error = e.message ?: "Could not load catalogue."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(mediaType) { load("") }
    BackHandler(enabled = selected != null) { selected = null }

    selected?.let { item ->
        TitleDetailPage(item = item, onBack = { selected = null }, onPlay = onPlay)
        return
    }

    Column(Modifier.fillMaxSize().background(CatalogueInk).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(heading, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Discover titles powered by TMDB", color = CatalogueMuted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().background(CataloguePanel, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search $heading…", color = CatalogueMuted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Search", color = CatalogueLime, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { load(query.trim()) }.padding(start = 12.dp))
        }
        Spacer(Modifier.height(14.dp))
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CatalogueLime)
            }
            error != null -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(error.orEmpty(), color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { load(query.trim()) }, colors = ButtonDefaults.buttonColors(containerColor = CatalogueLime, contentColor = CatalogueInk)) { Text("Try again") }
            }
            titles.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No titles found. Try another search.", color = CatalogueMuted)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(titles, key = { "${it.mediaType}-${it.id}" }) { item ->
                    Column(
                        Modifier.fillMaxWidth().clickable { selected = item },
                    ) {
                        AsyncImage(
                            model = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" },
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxWidth().height(230.dp).background(CataloguePanel, RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(
                            listOfNotNull(item.year.takeIf { it.isNotBlank() }, if (item.rating > 0) "★ ${String.format("%.1f", item.rating)}" else null).joinToString("  •  "),
                            color = CatalogueMuted, fontSize = 12.sp
                        )
                    }
                }
            }
        }
        Text("Movie and series information provided by TMDB. Nexvio is not endorsed or certified by TMDB.",
            color = CatalogueMuted, fontSize = 9.sp, modifier = Modifier.padding(vertical = 8.dp))
    }

}

@Composable
private fun TitleDetailPage(item: TmdbTitle, onBack: () -> Unit, onPlay: (TvChannel) -> Unit) {
    val year = item.year.ifBlank { "Unknown year" }
    val rating = if (item.rating > 0) String.format("%.1f", item.rating) else "N/A"
    Column(Modifier.fillMaxSize().background(CatalogueInk)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹  Back", color = CatalogueLime, fontWeight = FontWeight.Bold,
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
                        Text("★ $rating / 10", color = CatalogueLime, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("•", color = CatalogueMuted)
                        Text(if (item.mediaType == "movie") "Movie" else "Series", color = CatalogueMuted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Overview", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(item.overview.ifBlank { "No description is available for this title yet." }, color = CatalogueMuted, fontSize = 15.sp, lineHeight = 23.sp)
                    Spacer(Modifier.height(24.dp))
                    StreamDiscoverySection(item = item, onPlay = onPlay)
                    Spacer(Modifier.height(20.dp))
                    Text("Movie and series information provided by TMDB. Nexvio is not endorsed or certified by TMDB.", color = CatalogueMuted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}


private data class DiscoveredStream(
    val addonName: String,
    val title: String,
    val url: String,
    val quality: String
)

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

private suspend fun fetchAddonStreams(context: android.content.Context, item: TmdbTitle): List<DiscoveredStream> = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences("nexvio_addons", android.content.Context.MODE_PRIVATE)
    val installed = org.json.JSONArray(prefs.getString("installed", "[]") ?: "[]")
    val addons = (0 until installed.length()).mapNotNull { installed.optJSONObject(it) }
        .filter { it.optBoolean("enabled", true) }
        .mapNotNull { obj ->
            val name = obj.optString("name").ifBlank { "Add-on" }
            val manifest = obj.optString("manifestUrl").trim()
            if (manifest.startsWith("https://") || manifest.startsWith("http://")) name to manifest else null
        }
    if (addons.isEmpty()) throw IllegalStateException("No enabled add-ons are installed. Open Settings → Content providers to install a compatible Stremio add-on.")
    val imdbId = fetchTmdbImdbId(item)
    val resourceType = if (item.mediaType == "movie") "movie" else "series"
    // Stremio-compatible series endpoints require an episode ID. Phase 3C uses S1:E1
    // as the initial lookup; episode selection can be added in a later iteration.
    val streamId = if (item.mediaType == "movie") imdbId else "$imdbId:1:1"
    val output = mutableListOf<DiscoveredStream>()
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
                    output.add(DiscoveredStream(addonName, label, url, quality))
                }
            } finally { connection.disconnect() }
        } catch (_: Exception) {
            // A single unavailable add-on should not block results from other providers.
        }
    }
    output.distinctBy { it.url }.take(50)
}

@Composable
private fun StreamDiscoverySection(item: TmdbTitle, onPlay: (TvChannel) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var streams by remember(item.id, item.mediaType) { mutableStateOf<List<DiscoveredStream>>(emptyList()) }
    var loading by remember(item.id, item.mediaType) { mutableStateOf(false) }
    var searched by remember(item.id, item.mediaType) { mutableStateOf(false) }
    var error by remember(item.id, item.mediaType) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().background(CataloguePanel, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text("Where to watch", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (item.mediaType == "movie") "Search enabled Stremio-compatible add-ons for playable sources."
            else "Search enabled Stremio-compatible add-ons for season 1, episode 1. Episode selection is not available yet.",
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
                        streams = fetchAddonStreams(context, item)
                        if (streams.isEmpty()) error = "No streams were returned. The installed provider may use an unsupported protocol, or this title may not be available."
                    } catch (e: Exception) {
                        error = e.message ?: "Stream discovery failed."
                    } finally { loading = false }
                }
            },
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(containerColor = CatalogueLime, contentColor = CatalogueInk)
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
            Text("${streams.size} source(s) found", color = CatalogueLime, fontWeight = FontWeight.SemiBold)
            streams.forEach { stream ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().background(CatalogueInk, RoundedCornerShape(10.dp))
                        .clickable {
                            onPlay(TvChannel(
                                id = "addon-${item.mediaType}-${item.id}-${stream.url.hashCode()}",
                                name = "${item.title} • ${stream.quality}",
                                streamUrl = stream.url,
                                logoUrl = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" },
                                category = stream.addonName,
                                tvgId = null,
                                tvgName = stream.title,
                                isFavorite = false
                            ))
                        }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stream.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(stream.addonName, color = CatalogueMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stream.quality, color = CatalogueLime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("▶", color = CatalogueLime)
                }
            }
        } else if (searched && !loading && error == null) {
            Spacer(Modifier.height(8.dp))
            Text("No playable sources found.", color = CatalogueMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("Only direct HTTP(S) stream URLs from Stremio-compatible add-ons are listed. Nuvio JavaScript scraper repositories are not executable in Nexvio yet.", color = CatalogueMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}
