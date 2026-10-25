package com.nexvio.app

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

private data class TmdbTitle(
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
internal fun TmdbCatalogueScreen(mediaType: String) {
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

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.title) },
            text = {
                Column {
                    item.posterPath?.let {
                        AsyncImage(model = "$TMDB_IMAGE_BASE$it", contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(250.dp))
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(item.overview.ifBlank { "No description is available." })
                    Spacer(Modifier.height(8.dp))
                    Text("Year: ${item.year.ifBlank { "Unknown" }}  •  Rating: ${if (item.rating > 0) String.format("%.1f", item.rating) else "N/A"}")
                    Spacer(Modifier.height(8.dp))
                    Text("Stream availability depends on your installed compatible add-ons.", color = CatalogueMuted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close", color = CatalogueLime) } },
            containerColor = CataloguePanel,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}
