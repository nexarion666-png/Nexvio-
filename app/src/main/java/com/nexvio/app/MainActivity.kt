package com.nexvio.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private val Ink = Color(0xFF101114)
private val Panel = Color(0xFF1B1D22)
private val Lime = Color(0xFFB7F36B)
private val Muted = Color(0xFF9A9DA6)
private const val MAX_PLAYLIST_BYTES = 5 * 1024 * 1024

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexvioApp() }
    }
}

@Composable fun NexvioApp() {
    var current by remember { mutableStateOf("Home") }
    var detail by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<TvChannel?>(null) }
    MaterialTheme(colorScheme = darkColorScheme(primary=Lime, background=Ink, surface=Panel, onBackground=Color.White, onSurface=Color.White)) {
        Surface(Modifier.fillMaxSize(), color=Ink) {
            if (playing != null) {
                PlayerScreen(channel = playing!!, onBack = { playing = null })
            } else Column {
                Box(Modifier.weight(1f)) {
                    if (detail != null) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment=Alignment.CenterVertically) {
                                Text("‹  Back", color=Lime, modifier=Modifier.clickable { detail=null })
                                Spacer(Modifier.width(18.dp)); Text(detail!!, fontSize=20.sp, fontWeight=FontWeight.Bold)
                            }
                            InfoPage(detail!!)
                        }
                    } else when(current) {
                        "Home" -> HomeScreen(onOpen={ current=it }, onDetail={detail=it})
                        "Movies" -> CatalogueScreen("Movies", "Movie catalogue", onDetail={detail=it})
                        "Series" -> CatalogueScreen("Series", "Series catalogue", onDetail={detail=it})
                        "Live TV" -> LiveScreen(onDetail={detail=it}, onPlay={ playing=it })
                        "Library" -> LibraryScreen()
                        else -> SettingsScreen(onDetail={detail=it})
                    }
                }
                NavigationBar(containerColor=Panel) {
                    listOf("Home","Movies","Series","Live TV","Library","Settings").forEach { item ->
                        NavigationBarItem(selected=current==item, onClick={detail=null; current=item},
                            icon={Text(when(item){"Home"->"⌂";"Movies"->"▣";"Series"->"▤";"Live TV"->"◉";"Library"->"♡";else->"⚙"},fontSize=18.sp)},
                            label={Text(item,fontSize=10.sp)},
                            colors=NavigationBarItemDefaults.colors(selectedIconColor=Lime, selectedTextColor=Lime, indicatorColor=Ink))
                    }
                }
            }
        }
    }
}

@Composable private fun Brand() { Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(38.dp).background(Lime, RoundedCornerShape(12.dp)), contentAlignment=Alignment.Center) { Text("N", color=Ink, fontSize=25.sp, fontWeight=FontWeight.Black) }; Spacer(Modifier.width(10.dp)); Column { Text("NEXVIO", fontWeight=FontWeight.Black, letterSpacing=2.sp, fontSize=19.sp); Text("Movies • Series • Live TV", color=Muted, fontSize=11.sp) } } }
@Composable private fun HomeScreen(onOpen:(String)->Unit,onDetail:(String)->Unit) { LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) { item { Brand(); Spacer(Modifier.height(4.dp)); Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable { onDetail("Search") }.padding(14.dp), verticalAlignment=Alignment.CenterVertically) { Text("⌕", color=Lime, fontSize=22.sp); Spacer(Modifier.width(10.dp)); Text("Search movies, series…", color=Muted) } }; item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(20.dp)) { Text("YOUR NEXT STORY STARTS HERE", color=Lime, fontSize=10.sp, letterSpacing=1.5.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text("Entertainment,\nyour way.", fontSize=32.sp, lineHeight=36.sp, fontWeight=FontWeight.ExtraBold); Spacer(Modifier.height(8.dp)); Text("Your personal hub for movies, series and live TV.", color=Muted); Spacer(Modifier.height(16.dp)); Text("SET UP YOUR LIBRARY  →", color=Ink, fontWeight=FontWeight.Bold, modifier=Modifier.background(Lime, RoundedCornerShape(12.dp)).clickable { onDetail("Content providers") }.padding(horizontal=14.dp, vertical=11.dp)) } }; item { Text("EXPLORE", color=Muted, fontSize=11.sp, letterSpacing=2.sp) }; item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { listOf("Movies","Series","Live TV").forEach { name -> Column(Modifier.weight(1f).background(Panel, RoundedCornerShape(16.dp)).clickable { onOpen(name) }.padding(12.dp)) { Text(when(name){"Movies"->"▣";"Series"->"▤";else->"◉"}, color=Lime, fontSize=22.sp); Spacer(Modifier.height(12.dp)); Text(name, fontWeight=FontWeight.Bold); Text("Explore", color=Muted, fontSize=11.sp) } } } }; item { EmptySection("Recently watched", "Your watch history will appear here once playback is available.") }; item { EmptySection("Favorites", "Saved TV channels are available in Live TV.") }; item { Text("Catalogue demo layout only • No movie or series provider connected", color=Muted, fontSize=11.sp) } } }
@Composable private fun EmptySection(title:String, message:String) { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) { Text(title, fontSize=18.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(message, color=Muted, fontSize=13.sp); Spacer(Modifier.height(10.dp)); Text("FOUNDATION", color=Lime, fontSize=10.sp, letterSpacing=1.sp) } }
@Composable private fun CatalogueScreen(title:String, subtitle:String,onDetail:(String)->Unit) { Column(Modifier.fillMaxSize().padding(20.dp)) { Text(title, fontSize=30.sp, fontWeight=FontWeight.ExtraBold); Text(subtitle, color=Muted); Spacer(Modifier.height(18.dp)); Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable { onDetail("Search") }.padding(14.dp)) { Text("⌕  Search $title", color=Muted) }; Spacer(Modifier.height(20.dp)); EmptySection("Your $title collection", "Connect a compatible content provider in a later phase to browse real titles. No demonstration streams are provided.") } }
private class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexvio_iptv", Context.MODE_PRIVATE)
    fun source() = prefs.getString("source", null)
    fun label() = prefs.getString("label", null)
    fun favorites() = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun saveSource(source: String, label: String, local: Boolean) { prefs.edit().putString("source", source).putString("label", label).putBoolean("local", local).apply() }
    fun isLocal() = prefs.getBoolean("local", false)
    fun saveFavorites(ids: Set<String>) { prefs.edit().putStringSet("favorites", ids).apply() }
    fun clearSource() { prefs.edit().remove("source").remove("label").remove("local").apply() }
}

private suspend fun downloadPlaylist(urlText: String): String = withContext(Dispatchers.IO) {
    val uri = URI(urlText)
    require((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()) { "Enter a valid HTTP or HTTPS URL." }
    val conn = URL(urlText).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 12_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Nexvio/1.0 Android")
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("The server returned HTTP $code.")
        val length = conn.contentLengthLong
        if (length > MAX_PLAYLIST_BYTES) throw IllegalStateException("Playlist is larger than the 5 MB limit.")
        val out = conn.inputStream.buffered().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_PLAYLIST_BYTES) throw IllegalStateException("Playlist is larger than the 5 MB limit.")
                bytes.write(buffer, 0, n)
            }
            bytes.toByteArray()
        }
        out.toString(Charsets.UTF_8.name())
    } finally { conn.disconnect() }
}

@Composable private fun LiveScreen(onDetail:(String)->Unit, onPlay:(TvChannel)->Unit) {
    val context = LocalContext.current
    val store = remember { PlaylistStore(context) }
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var sourceLabel by remember { mutableStateOf(store.label()) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var urlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }
    val favorites = remember { mutableStateListOf<String>().also { it.addAll(store.favorites()) } }

    fun applyPlaylist(text: String, label: String) {
        val parsed = M3uParser.parse(text)
        if (parsed.channels.isEmpty()) { error = parsed.error ?: "No valid channels found."; channels = emptyList(); return }
        channels = parsed.channels.map { it.copy(isFavorite = it.id in favorites) }
        sourceLabel = label
        error = null
        loadedOnce = true
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                loading = true; error = null
                try {
                    try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            val output = StringBuilder()
                            val buffer = CharArray(8192)
                            var count = 0
                            while (true) {
                                val n = reader.read(buffer)
                                if (n < 0) break
                                count += n
                                if (count > MAX_PLAYLIST_BYTES) throw IllegalStateException("Playlist is larger than the 5 MB limit.")
                                output.append(buffer, 0, n)
                            }
                            output.toString()
                        } ?: throw IllegalStateException("Could not open the selected file.")
                    }
                    val label = uri.lastPathSegment?.substringAfterLast('/')?.take(80) ?: "Local playlist"
                    applyPlaylist(text, label)
                    if (channels.isNotEmpty()) store.saveSource(uri.toString(), label, true)
                } catch (e: Exception) { error = e.message?.take(180) ?: "Could not read this playlist file." }
                finally { loading = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!loadedOnce) {
            val saved = store.source()
            if (saved != null) {
                loading = true
                try {
                    if (store.isLocal()) {
                        val uri = Uri.parse(saved)
                        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw IllegalStateException("Local playlist access expired. Please re-import the file.") }
                        applyPlaylist(text, store.label() ?: "Local playlist")
                    } else {
                        applyPlaylist(downloadPlaylist(saved), store.label() ?: "Playlist URL")
                    }
                } catch (e: Exception) { error = e.message?.take(180) ?: "Could not reload the saved playlist." }
                finally { loading = false }
            }
        }
    }

    val categories = listOf("All") + channels.mapNotNull { it.category }.distinct().sorted()
    val filtered = channels.filter { (it.name.contains(search, true) || (it.tvgName?.contains(search, true) == true)) && (category == "All" || it.category == category) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(18.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Live TV", fontSize=30.sp, fontWeight=FontWeight.ExtraBold); Text("Import and browse your authorized IPTV channels", color=Muted) }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                Button(onClick={ filePicker.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream", "*/*")) }, modifier=Modifier.weight(1f), colors=ButtonDefaults.buttonColors(containerColor=Lime, contentColor=Ink)) { Text("Import file") }
                OutlinedButton(onClick={ urlInput=""; urlDialog=true }, modifier=Modifier.weight(1f)) { Text("Import URL") }
            }
        }
        if (sourceLabel != null) item {
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(sourceLabel!!, fontWeight=FontWeight.SemiBold, maxLines=1); Text("${channels.size} channels", color=Muted, fontSize=12.sp) }
                Text("Clear", color=Lime, modifier=Modifier.clickable { channels=emptyList(); sourceLabel=null; error=null; loadedOnce=true; store.clearSource() }.padding(8.dp))
            }
        }
        if (loading) item { Row(verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), color=Lime, strokeWidth=2.dp); Spacer(Modifier.width(12.dp)); Text("Loading playlist…", color=Muted) } }
        if (error != null) item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp)) { Text("Playlist issue", color=Lime, fontWeight=FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(error!!, color=Color.White, fontSize=13.sp); if (store.source() != null) Text("Check the source or re-import the playlist.", color=Muted, fontSize=12.sp) } }
        if (channels.isNotEmpty()) {
            item {
                OutlinedTextField(value=search, onValueChange={search=it}, modifier=Modifier.fillMaxWidth(), singleLine=true, label={Text("Search channels")}, placeholder={Text("Channel name")})
            }
            item {
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Category", color=Muted, modifier=Modifier.padding(end=8.dp)); var expanded by remember { mutableStateOf(false) }
                    Box { OutlinedButton(onClick={expanded=true}) { Text(category) }; DropdownMenu(expanded=expanded, onDismissRequest={expanded=false}) { categories.forEach { cat -> DropdownMenuItem(text={Text(cat)}, onClick={ category=cat; expanded=false }) } } }
                }
            }
            if (filtered.isEmpty()) item { EmptySection("No matching channels", "Try another search or category.") }
            items(filtered, key={it.id}) { channel ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable { onPlay(channel) }.padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
                    if (!channel.logoUrl.isNullOrBlank()) AsyncImage(model=channel.logoUrl, contentDescription=null, error=painterResource(id=R.drawable.ic_launcher), placeholder=painterResource(id=R.drawable.ic_launcher), modifier=Modifier.size(48.dp).background(Ink, RoundedCornerShape(10.dp)))
                    else Box(Modifier.size(48.dp).background(Ink, RoundedCornerShape(10.dp)), contentAlignment=Alignment.Center) { Text("▶", color=Lime, fontSize=20.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(channel.name, fontWeight=FontWeight.SemiBold, maxLines=2); Text(channel.category ?: "Uncategorized", color=Muted, fontSize=12.sp) }
                    Text(if (channel.id in favorites) "♥" else "♡", color=if (channel.id in favorites) Lime else Muted, fontSize=24.sp, modifier=Modifier.clickable {
                        if (channel.id in favorites) favorites.remove(channel.id) else favorites.add(channel.id)
                        store.saveFavorites(favorites.toSet())
                        channels = channels.map { it.copy(isFavorite = it.id in favorites) }
                    }.padding(8.dp))
                }
            }
        } else if (!loading && error == null) item { EmptySection("No playlist configured", "Import an .m3u or .m3u8 file, or enter a playlist URL. Only channels in a playlist you are authorized to use will be shown.") }
        item { Text("Some streams may be unavailable or use formats unsupported by this device.", color=Muted, fontSize=11.sp) }
    }

    if (urlDialog) AlertDialog(
        onDismissRequest={if (!loading) urlDialog=false},
        title={Text("Import playlist URL")},
        text={Column { Text("Enter a direct HTTP or HTTPS URL to an M3U/M3U8 text playlist.", color=Muted, fontSize=13.sp); Spacer(Modifier.height(10.dp)); OutlinedTextField(value=urlInput, onValueChange={urlInput=it}, label={Text("Playlist URL")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri), placeholder={Text("https://example.com/list.m3u")}) }},
        confirmButton={ TextButton(onClick={
            val input=urlInput.trim()
            val valid = try { val u=URI(input); (u.scheme.equals("http",true)||u.scheme.equals("https",true)) && !u.host.isNullOrBlank() } catch (_:Exception) { false }
            if (!valid) error="Enter a valid HTTP or HTTPS playlist URL."
            else {
                urlDialog=false
                scope.launch {
                    loading=true; error=null
                    try {
                        val text=downloadPlaylist(input)
                        val before=channels.size
                        applyPlaylist(text, "Playlist URL")
                        if (channels.isNotEmpty()) store.saveSource(input, "Playlist URL", false)
                        else if (before==0) error=error ?: "No valid channels found."
                    } catch (e:Exception) { error=e.message?.take(180) ?: "Could not download playlist." }
                    finally { loading=false }
                }
            }
        }) { Text("Import") }},
        dismissButton={TextButton(onClick={urlDialog=false}) { Text("Cancel") }}
    )
}

@Composable private fun PlayerScreen(channel: TvChannel, onBack:()->Unit) {
    val context=LocalContext.current
    val lifecycleOwner= LocalLifecycleOwner.current
    var isBuffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    val player = remember(channel.streamUrl, retry) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            playWhenReady = true
            addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) { isBuffering = playbackState == Player.STATE_BUFFERING; if (playbackState == Player.STATE_READY) playbackError = null }
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = when(error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Could not connect to this stream. Check the connection and try again."
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "This stream format is invalid or unsupported."
                        else -> "Playback failed. The stream may be unavailable or use an unsupported format."
                    }
                    isBuffering = false
                }
            })
            prepare()
        }
    }
    DisposableEffect(player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> { if (playbackError == null) player.play() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); player.release() }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullscreen) Row(Modifier.fillMaxWidth().background(Panel).padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
            Text("‹  Back", color=Lime, modifier=Modifier.clickable { onBack() })
            Spacer(Modifier.width(18.dp)); Text(channel.name, fontWeight=FontWeight.Bold, maxLines=1)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment=Alignment.Center) {
            AndroidView(factory={ ctx -> PlayerView(ctx).apply { this.player=player; useController=true } }, update={ it.player=player }, modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
            if (isBuffering && playbackError == null) CircularProgressIndicator(color=Lime)
            if (playbackError != null) Column(Modifier.padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                Text(playbackError!!, color=Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick={ playbackError=null; retry++ }, colors=ButtonDefaults.buttonColors(containerColor=Lime, contentColor=Ink)) { Text("Retry") }
            }
            if (fullscreen) Text("Exit fullscreen  ✕", color=Color.White, modifier=Modifier.align(Alignment.TopEnd).padding(14.dp).background(Panel, RoundedCornerShape(8.dp)).clickable { fullscreen=false }.padding(10.dp))
        }
        if (!fullscreen) Row(Modifier.fillMaxWidth().background(Panel).padding(16.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(channel.name, fontWeight=FontWeight.Bold); Text("Live stream • availability depends on the provider", color=Muted, fontSize=12.sp) }
            Text(if (isPlaying) "Pause" else "Play", color=Lime, modifier=Modifier.clickable { if (player.isPlaying) player.pause() else { playbackError=null; player.play() } }.padding(10.dp))
            Text("Fullscreen", color=Lime, modifier=Modifier.clickable { fullscreen=true }.padding(10.dp))
        }
    }
}

@Composable private fun LibraryScreen() { LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) { item { Text("Your Library", fontSize=30.sp, fontWeight=FontWeight.ExtraBold); Text("A home for your saved and watched titles", color=Muted) }; item { EmptySection("Favorites", "Saved TV channels are available in Live TV. Movie and series favorites require catalogue support.") }; item { EmptySection("Watch history", "Viewing history is not recorded in this phase.") }; item { EmptySection("Continue watching", "Playback progress is not persisted in Phase 2.") } } }
@Composable private fun SettingsScreen(onDetail:(String)->Unit) { LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) { item { Text("Settings", fontSize=30.sp, fontWeight=FontWeight.ExtraBold); Text("Make Nexvio yours", color=Muted); Spacer(Modifier.height(10.dp)) }; listOf("Appearance" to "Theme and visual preferences", "Playback preferences" to "Playback controls and defaults", "Content providers" to "Catalogue sources and addons", "IPTV playlists" to "Manage playlist sources", "Downloads" to "Offline media settings", "About Nexvio" to "Version and project information").forEach { (title,desc) -> item { Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable { onDetail(title) }.padding(16.dp), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Text(desc, color=Muted, fontSize=12.sp) }; Text("›", color=Lime, fontSize=24.sp) } } } } }
@Composable private fun InfoPage(title:String) { Column(Modifier.fillMaxWidth().padding(20.dp).background(Panel, RoundedCornerShape(18.dp)).padding(18.dp)) { Text(title, fontSize=23.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text(when(title) { "About Nexvio" -> "Nexvio 1.0.0 • Phase 2\nMovies • Series • Live TV\nAn original Android application."; "Search" -> "Search will become available when a real movie or series catalogue provider is connected."; "Appearance" -> "Nexvio currently uses its premium dark appearance."; "Playback preferences" -> "Live TV uses AndroidX Media3. Supported formats depend on stream and device compatibility."; "Content providers" -> "Movie and series provider integration is planned for a later phase."; "IPTV playlists" -> "Import an M3U/M3U8 file from Live TV or provide an HTTP/HTTPS playlist URL."; "Downloads" -> "Media downloads are not implemented."; else -> "This feature is planned for a later Nexvio phase." }, color=Muted, lineHeight=23.sp); Spacer(Modifier.height(14.dp)); Text("NEXVIO", color=Lime, fontSize=10.sp, letterSpacing=1.5.sp) } }
