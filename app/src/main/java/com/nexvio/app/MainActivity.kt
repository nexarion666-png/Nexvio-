package com.nexvio.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private val Ink: Color get() = AppAppearanceState.background
private val Panel = Color(0xFF1B1D22)
private val Accent: Color get() = AppAppearanceState.accent
private val Muted = Color(0xFF9A9DA6)
private const val MAX_PLAYLIST_BYTES = 5 * 1024 * 1024
private const val PLAYBACK_TAG = "NexvioPlayback"

private val tvChannelSaver = Saver<TvChannel?, Bundle>(
    save = { channel -> channel?.let { value ->
        Bundle().apply {
            putString("id", value.id)
            putString("name", value.name)
            putString("streamUrl", value.streamUrl)
            putString("requestHeaders", org.json.JSONObject(value.requestHeaders).toString())
            putString("logoUrl", value.logoUrl)
            putString("category", value.category)
            putString("tvgId", value.tvgId)
            putString("tvgName", value.tvgName)
            putBoolean("isFavorite", value.isFavorite)
        }
    } },
    restore = { state -> state?.let { value ->
        val streamUrl = value.getString("streamUrl")
        val name = value.getString("name")
        if (!streamUrl.isNullOrBlank() && !name.isNullOrBlank()) TvChannel(
            id = value.getString("id").orEmpty(),
            name = name,
            streamUrl = streamUrl,
            logoUrl = value.getString("logoUrl"),
            category = value.getString("category"),
            tvgId = value.getString("tvgId"),
            tvgName = value.getString("tvgName"),
            isFavorite = value.getBoolean("isFavorite"),
            requestHeaders = runCatching {
                val headers = org.json.JSONObject(value.getString("requestHeaders").orEmpty())
                buildMap { val keys = headers.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, headers.optString(key)) } }
            }.getOrDefault(emptyMap())
        ) else null
    } }
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

class MainActivity : ComponentActivity() {
    private var foregroundSyncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexvioApp(); NexvioUpdateChecker() }
    }

    override fun onStart() {
        super.onStart()
        // Sync sequentially while foregrounded. Each delay starts after the previous
        // sync finishes; failed attempts back off longer to avoid hammering the network.
        foregroundSyncJob?.cancel()
        foregroundSyncJob = lifecycleScope.launch {
            while (true) {
                val succeeded = syncNexvioAccountAutomatically(applicationContext)
                delay(if (succeeded) 5_000L else 15_000L)
            }
        }
    }

    override fun onStop() {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = null
        super.onStop()
    }
}

@Composable fun NexvioApp() {
    val appearanceContext = LocalContext.current
    val appearancePrefs = remember(appearanceContext, activeProfileIdForSettings(appearanceContext)) { settingsPrefs(appearanceContext) }
    LaunchedEffect(appearancePrefs) {
        AppAppearanceState.selectedTheme = appearancePrefs.getString("theme_name", "Emerald") ?: "Emerald"
        AppAppearanceState.amoledBlack = appearancePrefs.getBoolean("amoled_black", false)
        AppAppearanceState.initialized = true
    }
    val onboardingPrefs = remember(appearanceContext) {
        appearanceContext.getSharedPreferences("nexvio_onboarding", Context.MODE_PRIVATE)
    }
    var onboardingComplete by remember {
        mutableStateOf(
            onboardingPrefs.getBoolean(
                "complete",
                NexvioProfileStore.hasSavedProfiles(appearanceContext) || hasNexvioAccountSession(appearanceContext)
            )
        )
    }
    var showingWelcome by remember { mutableStateOf(!onboardingComplete) }
    var restoringAccountProfiles by remember { mutableStateOf(false) }
    var accountRestoreError by remember { mutableStateOf<String?>(null) }
    val onboardingScope = rememberCoroutineScope()
    var current by remember { mutableStateOf("Home") }
    var profileGate by remember { mutableStateOf(!NexvioProfileStore.hasSavedProfiles(appearanceContext)) }
    var showingProfilePicker by remember { mutableStateOf(false) }
    var activeProfileId by remember { mutableStateOf(NexvioProfileStore.activeId(appearanceContext)) }
    var searchKind by remember { mutableStateOf("movie") }
    var detail by remember { mutableStateOf<String?>(null) }
    var selectedTitle by remember { mutableStateOf<TmdbTitle?>(null) }
    var streamSearch by remember { mutableStateOf<Triple<TmdbTitle, Int, Int>?>(null) }
    var playing by rememberSaveable(stateSaver = tvChannelSaver) { mutableStateOf<TvChannel?>(null) }

    // If another device permanently deletes the active profile, switch this device to a
    // remaining profile as soon as the background account sync has removed it locally.
    LaunchedEffect(appearanceContext, activeProfileId) {
        while (true) {
            delay(2500)
            val profiles = NexvioProfileStore.all(appearanceContext)
            if (profiles.isNotEmpty() && profiles.none { it.id == activeProfileId }) {
                val next = profiles.firstOrNull { it.id == NexvioProfileStore.activeId(appearanceContext) } ?: profiles.first()
                NexvioProfileStore.setActive(appearanceContext, next.id)
                activeProfileId = next.id
                current = "Home"
                detail = null
                selectedTitle = null
                streamSearch = null
            }
        }
    }

    // Handle Back in screen order so it returns to the previous screen instead of closing the app.
    BackHandler(enabled = showingWelcome || profileGate || showingProfilePicker || playing != null || streamSearch != null || selectedTitle != null || detail != null || current != "Home") {
        when {
            showingWelcome -> { /* The welcome screen is the required first-launch destination. */ }
            profileGate -> { /* First profile creation is required until complete. */ }
            showingProfilePicker -> showingProfilePicker = false
            playing != null -> playing = null
            streamSearch != null -> streamSearch = null
            selectedTitle != null -> selectedTitle = null
            detail == "Add-ons" || detail == "Plugin repositories" -> detail = "Content providers"
            detail != null -> detail = null
            current != "Home" -> current = "Home"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary=Accent, background=Ink, surface=Panel, onBackground=Color.White, onSurface=Color.White)) {
        Surface(Modifier.fillMaxSize(), color=Ink) {
            if (restoringAccountProfiles) {
                Column(
                    Modifier.fillMaxSize().background(Ink).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(18.dp))
                    Text("Restoring your account and profiles…", color = Color.White, fontSize = 17.sp)
                }
            } else if (showingWelcome) {
                NexvioWelcomeScreen(
                    externalMessage = accountRestoreError,
                    onContinueWithoutAccount = {
                        onboardingPrefs.edit().putBoolean("complete", true).apply()
                        onboardingComplete = true
                        showingWelcome = false
                        profileGate = !NexvioProfileStore.hasSavedProfiles(appearanceContext)
                    },
                    onAuthenticated = {
                        // Restore cloud profiles before deciding whether this is a first-time account.
                        restoringAccountProfiles = true
                        accountRestoreError = null
                        onboardingScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    syncNexvioAccount(appearanceContext)
                                }
                                onboardingPrefs.edit().putBoolean("complete", true).apply()
                                onboardingComplete = true
                                showingWelcome = false
                                val profiles = NexvioProfileStore.all(appearanceContext)
                                profileGate = profiles.isEmpty()
                                if (profiles.isNotEmpty()) {
                                    val active = profiles.firstOrNull {
                                        it.id == NexvioProfileStore.activeId(appearanceContext)
                                    } ?: profiles.first()
                                    NexvioProfileStore.setActive(appearanceContext, active.id)
                                    activeProfileId = active.id
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                // Keep the user out of first-profile onboarding if cloud restore failed.
                                accountRestoreError = "Couldn't restore your account profiles. Check your connection and try again. " + (e.message ?: "")
                            } finally {
                                restoringAccountProfiles = false
                            }
                        }
                    }
                )
            } else if (profileGate || showingProfilePicker) {
                ProfileSystemScreen(
                    onProfileSelected = { profile ->
                        activeProfileId = profile.id
                        profileGate = false
                        showingProfilePicker = false
                        current = if (current == "Profile") "Profile" else "Home"
                        detail = null; selectedTitle = null; streamSearch = null
                    },
                    onExit = { profileGate = false; showingProfilePicker = false; current = "Profile" },
                    requireSelection = profileGate
                )
            } else if (playing != null) {
                PlayerScreen(channel = playing!!, profileId = activeProfileId, onBack = { playing = null })
            } else Column {
                Box(Modifier.weight(1f)) {
                    if (streamSearch != null) {
                        val request = streamSearch!!
                        StreamSearchPage(item = request.first, seasonNumber = request.second, episodeNumber = request.third, onBack = { streamSearch = null }, onPlay = { playing = it })
                    } else if (selectedTitle != null) {
                        TitleDetailPage(item = selectedTitle!!, onBack = { selectedTitle = null }, onPlay = { playing = it }, onFindStreams = { item, season, episode -> streamSearch = Triple(item, season, episode) }, profileId = activeProfileId)
                    } else if (detail == "Content providers") {
                        ContentProvidersScreen(
                            onOpenAddons = { detail = "Add-ons" },
                            onOpenRepositories = { detail = "Plugin repositories" },
                            onBack = { detail = null }
                        )
                    } else if (detail == "Add-ons") {
                        AddonManagerScreen(onBack = { detail = "Content providers" })
                    } else if (detail == "Plugin repositories") {
                        PluginRepositoryScreen(onBack = { detail = "Content providers" })
                    } else if (detail == "Watch history") {
                        WatchHistoryScreen(profileId = activeProfileId, onPlay = { playing = it })
                    } else if (detail == "Layout") {
                        LayoutSettingsScreenV2(onBack = { detail = null })
                    } else if (detail == "Content & Discovery") {
                        ContentProvidersScreen(
                            onOpenAddons = { detail = "Add-ons" },
                            onOpenRepositories = { detail = "Plugin repositories" },
                            onBack = { detail = null }
                        )
                    } else if (detail == "Playback") {
                        PlaybackSettingsScreenV2(onBack = { detail = null })
                    } else if (detail == "Account") {
                        AccountSettingsScreen(
                            onBack = { detail = null },
                            onSignedOut = {
                                onboardingPrefs.edit().putBoolean("complete", false).apply()
                                onboardingComplete = false
                                showingWelcome = true
                                restoringAccountProfiles = false
                                accountRestoreError = null
                                current = "Home"
                                detail = null
                                selectedTitle = null
                                streamSearch = null
                                playing = null
                                showingProfilePicker = false
                                profileGate = false
                            }
                        )
                    } else if (detail == "Integrations") {
                        IntegrationsSettingsScreen(onBack = { detail = null })
                    } else if (detail == "Notifications") {
                        NotificationsSettingsScreen(onBack = { detail = null })
                    } else if (detail != null) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment=Alignment.CenterVertically) {
                                Text("‹  Back", color=Accent, modifier=Modifier.clickable { detail=null })
                                Spacer(Modifier.width(18.dp)); Text(detail!!, fontSize=20.sp, fontWeight=FontWeight.Bold)
                            }
                            if (detail == "Downloads") DownloadsScreen(onPlay = { playing = it }, profileId = activeProfileId) else InfoPage(detail!!)
                        }
                    } else when(current) {
                        "Home" -> HomeScreen(profileId = activeProfileId, onOpen={ item -> searchKind = if (item == "Series") "tv" else "movie"; current = if (item == "Live TV") "Live TV" else "Search" }, onDetail={detail=it}, onPlay={ playing=it }, onTitleSelected={ selectedTitle = it })
                        "Search" -> TmdbCatalogueScreen(searchKind, onPlay = { playing = it }, onTitleSelected = { selectedTitle = it }, profileId = activeProfileId)
                        "Live TV" -> LiveScreen(onDetail={detail=it}, onPlay={ playing=it })
                        "Library" -> OfflineLibraryScreen(onPlay = { playing = it }, onTitleSelected = { selectedTitle = it }, profileId = activeProfileId)
                        else -> SettingsScreen(onDetail={ item ->
                        if (item == "Switch Profile") {
                            showingProfilePicker = true
                        } else {
                            detail = item
                        }
                    })
                    }
                }
                val navPrefs = settingsPrefs(appearanceContext)
                val navStyle = navPrefs.getString("navigation_style", "Adaptive") ?: "Adaptive"
                val navGlow = navPrefs.getBoolean("navigation_glow", true)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = if (navStyle == "Compact") 6.dp else 12.dp, vertical = if (navStyle == "Compact") 5.dp else 10.dp)
                        .background(Panel.copy(alpha = 0.97f), RoundedCornerShape(40.dp))
                        .padding(horizontal = 5.dp, vertical = if (navStyle == "Compact") 2.dp else 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("Home","Search","Live TV","Library","Profile").forEach { item ->
                        val selected = current == item && selectedTitle == null && detail == null
                        Column(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(32.dp))
                                .background(if (selected && navGlow) Accent.copy(alpha = 0.16f) else if (selected) Ink else Color.Transparent)
                                .clickable { streamSearch = null; detail = null; selectedTitle = null; current = item }
                                .padding(vertical = if (navStyle == "Compact") 5.dp else 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (navStyle == "Compact") 1.dp else 4.dp)
                        ) {
                            Text(when(item) { "Home" -> "⌂"; "Search" -> "⌕"; "Live TV" -> "⊙"; "Library" -> "▤"; else -> "●" },
                                color = if (selected) Accent else Muted, fontSize = if (navStyle == "Compact") 18.sp else 21.sp, lineHeight = 23.sp)
                            if (navStyle != "Minimal") Text(item, color = if (selected) Accent else Muted, fontSize = if (navStyle == "Compact") 9.sp else 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Brand() { Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(38.dp).background(Accent, RoundedCornerShape(12.dp)), contentAlignment=Alignment.Center) { Text("N", color=Ink, fontSize=25.sp, fontWeight=FontWeight.Black) }; Spacer(Modifier.width(10.dp)); Column { Text("NEXVIO", fontWeight=FontWeight.Black, letterSpacing=2.sp, fontSize=19.sp); Text("Movies • Series • Live TV", color=Muted, fontSize=11.sp) } } }
@Composable private fun HomeScreen(profileId: String, onOpen:(String)->Unit,onDetail:(String)->Unit,onPlay:(TvChannel)->Unit,onTitleSelected:(TmdbTitle)->Unit) {
    val context = LocalContext.current
    val prefs = remember(context, profileId) { settingsPrefs(context) }
    val collectionPrefs = remember(context) { context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE) }
    var collectionRevision by remember(collectionPrefs) { mutableIntStateOf(0) }
    DisposableEffect(collectionPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "layout_collections" || key == "layout_collection_items") collectionRevision++
        }
        collectionPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { collectionPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val customCollections = remember(collectionPrefs, collectionRevision) {
        CustomCollectionStore.names(context).map { name -> name to CustomCollectionStore.titles(context, name) }.filter { it.second.isNotEmpty() }
    }
    var posterSettingsRevision by remember(prefs) { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("home_style", "show_descriptions", "poster_size", "poster_card_width", "poster_corner_radius", "continue_watching_enabled", "continue_watching_completed")) {
                posterSettingsRevision++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val homeStyle = remember(prefs, posterSettingsRevision) { prefs.getString("home_style", "Featured") ?: "Featured" }
    val showDescriptions = remember(prefs, posterSettingsRevision) { prefs.getBoolean("show_descriptions", true) }
    val posterSize = remember(prefs, posterSettingsRevision) { prefs.getString("poster_size", "Comfortable") ?: "Comfortable" }
    val posterWidth = remember(prefs, posterSettingsRevision) { prefs.getFloat("poster_card_width", 126f) * when (posterSize) { "Compact" -> 0.82f; "Large" -> 1.15f; else -> 1f } }
    val posterRadius = remember(prefs, posterSettingsRevision) { prefs.getFloat("poster_corner_radius", 13f) }
    val continueWatchingEnabled = remember(prefs, posterSettingsRevision) { prefs.getBoolean("continue_watching_enabled", true) }
    val showCompletedInContinueWatching = remember(prefs, posterSettingsRevision) { prefs.getBoolean("continue_watching_completed", false) }
    val historyStore = remember(context, profileId) { WatchHistoryStore(context, profileId) }
    var watchEntries by remember { mutableStateOf(historyStore.all()) }
    var movies by remember { mutableStateOf<List<TmdbTitle>>(emptyList()) }
    var series by remember { mutableStateOf<List<TmdbTitle>>(emptyList()) }
    var featuredIndex by remember { mutableIntStateOf(0) }
    val featuredTitles = remember(movies, series) { (movies + series).distinctBy { it.mediaType + "-" + it.id } }
    LaunchedEffect(Unit) {
        while (true) { watchEntries = historyStore.all(); delay(1500) }
    }
    LaunchedEffect(Unit) {
        try { movies = fetchTmdbTitles("movie", "").take(10); series = fetchTmdbTitles("tv", "").take(10) } catch (_: Exception) { }
    }
    LaunchedEffect(featuredTitles.size) {
        if (featuredTitles.size > 1) {
            while (true) {
                delay(5000)
                featuredIndex = (featuredIndex + 1) % featuredTitles.size
            }
        }
    }
    LazyColumn(contentPadding=PaddingValues(bottom=22.dp), verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp).padding(top=20.dp), verticalAlignment=Alignment.CenterVertically) {
                Brand()
            }
        }
        item {
            val featured = featuredTitles.getOrNull(if (featuredTitles.isEmpty()) 0 else featuredIndex % featuredTitles.size)
            if (homeStyle != "Grid") Box(Modifier.fillMaxWidth().padding(horizontal=18.dp).height(if (homeStyle == "Compact") 240.dp else 380.dp).background(Panel,RoundedCornerShape(24.dp)).clickable { featured?.let(onTitleSelected) }) {
                if (featured?.posterPath != null) AsyncImage(model="https://image.tmdb.org/t/p/w780${featured.posterPath}",contentDescription=featured.title,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color(0xCC101114),RoundedCornerShape(24.dp)))
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text("FEATURED TODAY",color=Accent,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(featured?.title ?: "Find your next favourite",color=Color.White,fontSize=29.sp,lineHeight=34.sp,fontWeight=FontWeight.ExtraBold,maxLines=2)
                    Spacer(Modifier.height(8.dp))
                    if (showDescriptions && homeStyle != "Compact") {
                        Text(featured?.overview?.takeIf{it.isNotBlank()} ?: "Discover trending movies, popular series and live TV.",color=Color.White.copy(alpha=.82f),fontSize=13.sp,lineHeight=19.sp,maxLines=3)
                        Spacer(Modifier.height(15.dp))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("▶  Explore movies",color=Color.White,fontWeight=FontWeight.Bold,modifier=Modifier.background(Accent,RoundedCornerShape(11.dp)).clickable { onOpen("Movies") }.padding(horizontal=14.dp,vertical=11.dp))
                        Text("Series",color=Color.White,fontWeight=FontWeight.Bold,modifier=Modifier.background(Panel,RoundedCornerShape(11.dp)).clickable { onOpen("Series") }.padding(horizontal=14.dp,vertical=11.dp))
                    }
                }
            }
        }
        item { HomeTitleRow("Trending Movies","See all") { onOpen("Movies") } }
        if (homeStyle == "Grid") {
            items(movies.chunked(2).size) { index ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    movies.chunked(2)[index].forEach { title -> HomePosterCard(title, onTitleSelected, posterWidth, posterRadius, showDescriptions, Modifier.weight(1f), fillCell = true) }
                    if (movies.chunked(2)[index].size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            item { HomePosterRow(movies, onTitleSelected, posterWidth, posterRadius, showDescriptions) }
        }
        item { HomeTitleRow("Popular Series","See all") { onOpen("Series") } }
        if (homeStyle == "Grid") {
            items(series.chunked(2).size) { index ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    series.chunked(2)[index].forEach { title -> HomePosterCard(title, onTitleSelected, posterWidth, posterRadius, showDescriptions, Modifier.weight(1f)) }
                    if (series.chunked(2)[index].size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            item { HomePosterRow(series, onTitleSelected, posterWidth, posterRadius, showDescriptions) }
        }
        customCollections.forEach { (collectionName, collectionTitles) ->
            item { HomeTitleRow(collectionName, "Edit") { onDetail("Layout") } }
            if (homeStyle == "Grid") {
                items(collectionTitles.chunked(2).size) { index ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        collectionTitles.chunked(2)[index].forEach { title ->
                            HomePosterCard(title, onTitleSelected, posterWidth, posterRadius, showDescriptions, Modifier.weight(1f), fillCell = true)
                        }
                        if (collectionTitles.chunked(2)[index].size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                item { HomePosterRow(collectionTitles, onTitleSelected, posterWidth, posterRadius, showDescriptions) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                HomeQuickCard("Live TV","Watch channels",Modifier.weight(1f)) { onOpen("Live TV") }
                HomeQuickCard("My Library","Your downloads",Modifier.weight(1f)) { onDetail("Downloads") }
            }
        }
        if (continueWatchingEnabled) {
            item {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    val continueEntries = watchEntries.filter { it.positionMs > 0L && (showCompletedInContinueWatching || !it.completed) }.take(5)
                    WatchHistorySection(
                        title = "Continue watching",
                        entries = continueEntries,
                        onPlay = onPlay,
                        onSeeAll = { onDetail("Watch history") },
                        onRemove = { entry -> historyStore.remove(entry.id); watchEntries = historyStore.all() }
                    )
                }
            }
        }
        item { Column(Modifier.padding(horizontal=18.dp)) { WatchHistorySection("Recently watched",watchEntries.take(5),onPlay,onSeeAll={onDetail("Watch history")}) } }
    }
}

@Composable private fun HomeTitleRow(title:String,action:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(action,color=Accent,fontSize=12.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.clickable(onClick=onClick))
    }
}

@Composable private fun HomePosterRow(titles:List<TmdbTitle>,onClick:(TmdbTitle)->Unit,posterWidth:Float=126f,posterRadius:Float=13f,showDescriptions:Boolean=true) {
    if(titles.isEmpty()) Text("Loading titles…",color=Muted,modifier=Modifier.padding(horizontal=18.dp))
    else LazyRow(contentPadding=PaddingValues(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        items(titles,key={"poster-${it.mediaType}-${it.id}"}) { title ->
            HomePosterCard(title,onClick,posterWidth,posterRadius,showDescriptions)
        }
    }
}

@Composable private fun HomePosterCard(title:TmdbTitle,onClick:(TmdbTitle)->Unit,posterWidth:Float,posterRadius:Float,showDescriptions:Boolean,modifier:Modifier=Modifier,fillCell:Boolean=false) {
    val cardModifier = if (fillCell) modifier.fillMaxWidth() else modifier.width(posterWidth.dp)
    Column(cardModifier.clickable { onClick(title) }) {
        AsyncImage(model=title.posterPath?.let{"https://image.tmdb.org/t/p/w500$it"},contentDescription=title.title,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().height((posterWidth*1.45f).dp).clip(RoundedCornerShape(posterRadius.dp)).background(Panel,RoundedCornerShape(posterRadius.dp)))
        Spacer(Modifier.height(7.dp))
        Text(title.title,color=Color.White,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=2)
        if(showDescriptions) Text(title.year,color=Muted,fontSize=11.sp)
    }
}

@Composable private fun HomeQuickCard(title:String,subtitle:String,modifier:Modifier=Modifier,onClick:()->Unit) {
    Column(modifier.background(Panel,RoundedCornerShape(16.dp)).clickable(onClick=onClick).padding(15.dp)) {
        Text(title,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle,color=Muted,fontSize=12.sp)
    }
}
@Composable private fun EmptySection(title:String, message:String) { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) { Text(title, fontSize=18.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(message, color=Muted, fontSize=13.sp); Spacer(Modifier.height(10.dp)); Text("FOUNDATION", color=Accent, fontSize=10.sp, letterSpacing=1.sp) } }
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
        out.toString(Charsets.UTF_8)
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
                Button(onClick={ filePicker.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream", "*/*")) }, modifier=Modifier.weight(1f), colors=ButtonDefaults.buttonColors(containerColor=Accent, contentColor=Ink)) { Text("Import file") }
                OutlinedButton(onClick={ urlInput=""; urlDialog=true }, modifier=Modifier.weight(1f)) { Text("Import URL") }
            }
        }
        if (sourceLabel != null) item {
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(sourceLabel!!, fontWeight=FontWeight.SemiBold, maxLines=1); Text("${channels.size} channels", color=Muted, fontSize=12.sp) }
                Text("Clear", color=Accent, modifier=Modifier.clickable { channels=emptyList(); sourceLabel=null; error=null; loadedOnce=true; store.clearSource() }.padding(8.dp))
            }
        }
        if (loading) item { Row(verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), color=Accent, strokeWidth=2.dp); Spacer(Modifier.width(12.dp)); Text("Loading playlist…", color=Muted) } }
        if (error != null) item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp)) { Text("Playlist issue", color=Accent, fontWeight=FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(error!!, color=Color.White, fontSize=13.sp); if (store.source() != null) Text("Check the source or re-import the playlist.", color=Muted, fontSize=12.sp) } }
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
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable { Log.d(PLAYBACK_TAG, "Selected channel name=${channel.name} streamUrl=${channel.streamUrl}"); onPlay(channel) }.padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
                    if (!channel.logoUrl.isNullOrBlank()) AsyncImage(model=channel.logoUrl, contentDescription=null, error=painterResource(id=R.drawable.ic_launcher), placeholder=painterResource(id=R.drawable.ic_launcher), modifier=Modifier.size(48.dp).background(Ink, RoundedCornerShape(10.dp)))
                    else Box(Modifier.size(48.dp).background(Ink, RoundedCornerShape(10.dp)), contentAlignment=Alignment.Center) { Text("▶", color=Accent, fontSize=20.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(channel.name, fontWeight=FontWeight.SemiBold, maxLines=2); Text(channel.category ?: "Uncategorized", color=Muted, fontSize=12.sp) }
                    Text(if (channel.id in favorites) "♥" else "♡", color=if (channel.id in favorites) Accent else Muted, fontSize=24.sp, modifier=Modifier.clickable {
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

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3_600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(totalSeconds / 60, seconds)
}

@Composable private fun PlayerScreen(channel: TvChannel, profileId: String, onBack:()->Unit) {
    val context = LocalContext.current
    val playerPrefs = remember(context) { settingsPrefs(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fullscreenOverride by rememberSaveable(channel.id) { mutableStateOf<Boolean?>(null) }
    val fullscreen = fullscreenOverride ?: isLandscape
    var resumeAfterLifecyclePause by remember { mutableStateOf(false) }
    var isBuffering by remember(channel.id) { mutableStateOf(true) }
    var isPlaying by remember(channel.id) { mutableStateOf(false) }
    var playbackError by remember(channel.id) { mutableStateOf<String?>(null) }
    var playbackDetails by remember(channel.id) { mutableStateOf<String?>(null) }
    var retry by remember(channel.id) { mutableIntStateOf(0) }
    var savedPositionMs by rememberSaveable(channel.id) { mutableLongStateOf(WatchHistoryStore(context, profileId).get(channel.id)?.positionMs ?: 0L) }
    var shouldResumePlayback by rememberSaveable(channel.id) { mutableStateOf(true) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playbackSpeed by rememberSaveable(channel.id) { mutableFloatStateOf(1f) }
    var controlsVisible by rememberSaveable(channel.id) { mutableStateOf(true) }
    var controlsInteraction by remember(channel.id) { mutableIntStateOf(0) }
    var screenLocked by rememberSaveable(channel.id) { mutableStateOf(false) }
    var muted by rememberSaveable(channel.id) { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var trackDialogOpen by remember { mutableStateOf(false) }
    var positionMs by remember(channel.id) { mutableLongStateOf(0L) }
    var scrubPositionMs by remember(channel.id) { mutableStateOf<Long?>(null) }
    var durationMs by remember(channel.id) { mutableLongStateOf(0L) }
    var brightnessLevel by rememberSaveable { mutableFloatStateOf(0.6f) }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    fun revealControls() { controlsVisible = true; controlsInteraction++ }
    fun showPlayerMessage(message: String) { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() }
    fun openTrackDialog(trackType: Int, title: String, showDisableOption: Boolean = false) {
        val active = player ?: run { showPlayerMessage("Player is not ready yet."); return }
        if (active.currentTracks.groups.none { it.type == trackType }) {
            showPlayerMessage("This stream does not provide selectable $title.")
            return
        }
        val builder = TrackSelectionDialogBuilder(context, title, active, trackType)
        if (showDisableOption) builder.setShowDisableOption(true)
        val dialog = builder.build()
        trackDialogOpen = true
        dialog.setOnDismissListener {
            trackDialogOpen = false
            revealControls()
        }
        dialog.show()
        revealControls()
    }
    fun cyclePlaybackSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val next = speeds[(speeds.indexOf(playbackSpeed).takeIf { it >= 0 }?.plus(1) ?: 0) % speeds.size]
        playbackSpeed = next
        player?.setPlaybackSpeed(next)
        revealControls()
    }

    val watchHistory = remember(context, profileId) { WatchHistoryStore(context, profileId) }
    LaunchedEffect(channel.id) { if (channel.id.startsWith("addon-")) watchHistory.record(channel, savedPositionMs, watchHistory.get(channel.id)?.durationMs ?: 0L) }

    // Landscape is fullscreen by default. Back exits fullscreen first; rotating again
    // clears the temporary override so orientation-driven fullscreen works normally.
    LaunchedEffect(configuration.orientation) { fullscreenOverride = null }
    LaunchedEffect(fullscreen, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            // Hide status and navigation bars at the Activity window level. Transient
            // bars may appear for a deliberate system gesture and Android hides them again.
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }
    BackHandler(enabled = fullscreen) {
        fullscreenOverride = false
    }

    // Create/release the player in an effect, never while Compose is evaluating a
    // remember block. The URI is passed through unchanged; only known HLS paths
    // receive an explicit HLS MIME type.
    DisposableEffect(channel.streamUrl, retry) {
        isBuffering = true
        isPlaying = false
        playbackError = null
        playbackDetails = null
        Log.i(PLAYBACK_TAG, "Preparing channel name=${channel.name} host=${Uri.parse(channel.streamUrl).host ?: "unknown"}")
        var createdPlayer: ExoPlayer? = null
        try {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(channel.requestHeaders["User-Agent"] ?: "Nexvio/1.0 (Android)")
                .setDefaultRequestProperties(channel.requestHeaders.filterKeys { !it.equals("User-Agent", true) })
                .setAllowCrossProtocolRedirects(true)
            val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val minBufferMs = (playerPrefs.getInt("playback_min_buffer", 15).coerceIn(5, 60) * 1_000)
            val maxBufferMs = maxOf(minBufferMs, playerPrefs.getInt("playback_max_buffer", 45).coerceIn(15, 120) * 1_000)
            val startBufferMs = playerPrefs.getInt("playback_start_buffer", 5).coerceIn(1, 30) * 1_000
            val backBufferMs = playerPrefs.getInt("playback_back_buffer", 10).coerceIn(0, 60) * 1_000
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, maxBufferMs, startBufferMs.coerceAtMost(minBufferMs), 3_000)
                .setBackBuffer(backBufferMs, true)
                .build()
            val instance = ExoPlayer.Builder(context.applicationContext)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .build()
            createdPlayer = instance
            player = instance
            instance.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) {
                        playbackError = null
                        playbackDetails = null
                        // For seekable/VOD sources, restore a saved position once. Do not
                        // seek live IPTV streams away from their default live position.
                        if (playerPrefs.getBoolean("playback_resume", true) && savedPositionMs > 0L && !instance.isCurrentMediaItemLive) {
                            instance.seekTo(savedPositionMs)
                            savedPositionMs = 0L
                        }
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    shouldResumePlayback = playWhenReady
                }

                override fun onPlayerError(error: PlaybackException) {
                    val failure = describePlaybackFailure(error)
                    playbackError = failure.message
                    playbackDetails = "${failure.details}\nHost: ${Uri.parse(channel.streamUrl).host ?: "unknown"}"
                    isBuffering = false
                    isPlaying = false
                    Log.e(
                        PLAYBACK_TAG,
                        "Playback failed channel=${channel.name} streamUrl=${channel.streamUrl} details=${failure.details}",
                        error
                    )
                }
            })
            val uri = Uri.parse(channel.streamUrl)
            val mediaItem = MediaItem.Builder().setUri(uri).apply {
                if (uri.path?.endsWith(".m3u8", ignoreCase = true) == true) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }.build()
            instance.setMediaItem(mediaItem)
            instance.playWhenReady = shouldResumePlayback && playerPrefs.getBoolean("playback_autoplay", true)
            instance.prepare()
        } catch (e: Exception) {
            Log.e(PLAYBACK_TAG, "Player initialization failed for channel=${channel.name} host=${Uri.parse(channel.streamUrl).host ?: "unknown"}", e)
            createdPlayer?.release()
            createdPlayer = null
            player = null
            playbackError = "Nexvio could not initialize playback for this channel."
            playbackDetails = "${e.javaClass.simpleName}: ${e.message ?: "No detail provided"}\nHost: ${Uri.parse(channel.streamUrl).host ?: "unknown"}"
            isBuffering = false
            isPlaying = false
        }

        onDispose {
            val oldPlayer = createdPlayer
            if (oldPlayer != null && player === oldPlayer) player = null
            oldPlayer?.release()
        }
    }

    LaunchedEffect(player) {
        val activePlayer = player ?: return@LaunchedEffect
        var saveTick = 0
        while (true) {
            delay(1_000)
            saveTick++
            shouldResumePlayback = activePlayer.playWhenReady
            positionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            durationMs = activePlayer.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            // Live streams should reopen at the provider's live edge. Preserve position
            // only for non-live media that supports a meaningful seek position.
            if (activePlayer.playbackState == Player.STATE_READY && !activePlayer.isCurrentMediaItemLive) {
                savedPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
                if (channel.id.startsWith("addon-") && saveTick % 5 == 0) {
                    watchHistory.record(channel, savedPositionMs, activePlayer.duration.takeIf { it > 0L } ?: 0L)
                }
            }
        }
    }

    LaunchedEffect(controlsVisible, channel.id, controlsInteraction, screenLocked, playbackError, moreMenuExpanded, trackDialogOpen, isPlaying) {
        // Keep controls visible while paused, while an error needs attention, or while
        // a track-selection dialog/menu is open. Resume the timer when playback resumes.
        if (controlsVisible && isPlaying && playbackError == null && !screenLocked && !moreMenuExpanded && !trackDialogOpen) {
            delay(3_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(player, muted) { player?.volume = if (muted) 0f else 1f }

    DisposableEffect(player, lifecycleOwner) {
        val activePlayer = player
        if (activePlayer == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        resumeAfterLifecyclePause = activePlayer.playWhenReady
                        activePlayer.pause()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        if (resumeAfterLifecyclePause && activePlayer.playerError == null) activePlayer.play()
                        resumeAfterLifecyclePause = false
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                if (channel.id.startsWith("addon-")) {
                    val position = activePlayer.currentPosition.coerceAtLeast(0L)
                    val duration = activePlayer.duration.takeIf { it > 0L } ?: 0L
                    watchHistory.record(channel, position, duration)
                }
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black), verticalArrangement = Arrangement.Center) {
        if (!fullscreen) Spacer(Modifier.weight(1f))
        Box((if (fullscreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)).background(Color.Black), contentAlignment=Alignment.Center) {
            if (player != null) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply {
                        useController = false
                        resizeMode = when (playerPrefs.getString("playback_resize", "Fit")) {
                            "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        setKeepContentOnPlayerReset(true)
                        this.player = player
                    } },
                    update = { view ->
                        view.resizeMode = when (playerPrefs.getString("playback_resize", "Fit")) {
                            "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        if (view.player !== player) view.player = player
                    },
                    onRelease = { view -> view.player = null },
                    modifier = (if (fullscreen) Modifier.fillMaxSize()
                        else Modifier.fillMaxWidth().aspectRatio(16f / 9f)).pointerInput(player) {
                            detectTapGestures(
                                onTap = { if (!screenLocked) { controlsVisible = !controlsVisible; controlsInteraction++ } },
                                onDoubleTap = { offset ->
                                    if (!screenLocked) {
                                        val active = player
                                        if (active != null && active.isCurrentMediaItemSeekable) {
                                            if (offset.x < size.width / 2f) active.seekBack() else active.seekForward()
                                        }
                                        revealControls()
                                    }
                                }
                            )
                        }
                )
            }
            if (isBuffering && playbackError == null && playerPrefs.getBoolean("playback_loading_overlay", true)) CircularProgressIndicator(color = Accent)
            if (playbackError != null) Column(Modifier.padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                Text(playbackError!!, color=Color.White)
                if (!playbackDetails.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Technical details: ${playbackDetails}", color=Muted, fontSize=11.sp)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nexvio playback error", playbackDetails.orEmpty()))
                    }) { Text("Copy", color=Accent) }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick={ playbackError=null; shouldResumePlayback=true; retry++ }, colors=ButtonDefaults.buttonColors(containerColor=Accent, contentColor=Ink)) { Text("Retry") }
            }
            if (screenLocked) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 18.dp)
                        .size(46.dp).clip(CircleShape).background(Color(0x99080A0F))
                        .clickable { screenLocked = false; revealControls() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = "Unlock controls", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            } else {
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(animationSpec = tween(durationMillis = 180)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 220)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.align(Alignment.Center)
                                .background(Color(0x66000000), RoundedCornerShape(48.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerIconButton(
                                Icons.Filled.Replay10,
                                "Rewind 10 seconds",
                                {
                                    player?.let { if (it.isCurrentMediaItemSeekable) it.seekBack() }
                                    revealControls()
                                },
                                size = 34,
                                enabled = player?.isCurrentMediaItemSeekable == true
                            )
                            Box(
                                Modifier.size(64.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .clickable {
                                        player?.let { active ->
                                            if (active.isPlaying) active.pause()
                                            else {
                                                playbackError = null
                                                playbackDetails = null
                                                shouldResumePlayback = true
                                                active.play()
                                            }
                                        } ?: run {
                                            playbackError = null
                                            shouldResumePlayback = true
                                            retry++
                                        }
                                        revealControls()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause playback" else "Start playback",
                                    tint = Color.White,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                            PlayerIconButton(
                                Icons.Filled.Forward10,
                                "Forward 10 seconds",
                                {
                                    player?.let { if (it.isCurrentMediaItemSeekable) it.seekForward() }
                                    revealControls()
                                },
                                size = 34,
                                enabled = player?.isCurrentMediaItemSeekable == true
                            )
                        }
                        Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)))
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 20.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        PlayerIconButton(Icons.Filled.ArrowBack, "Back", {
                            onBack()
                        }, size = 26)
                        Text(
                            channel.name,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 3.dp)
                        )
                        PlayerIconButton(Icons.Filled.Cast, "Cast", {
                            showPlayerMessage("Casting is not configured in this build.")
                            revealControls()
                        }, size = 23)
                        PlayerIconButton(Icons.Filled.HighQuality, "Video quality", { openTrackDialog(C.TRACK_TYPE_VIDEO, "Video quality") }, size = 25)
                        PlayerIconButton(Icons.Filled.ClosedCaption, "Subtitles", { openTrackDialog(C.TRACK_TYPE_TEXT, "Subtitles", true) }, size = 24)
                        PlayerIconButton(Icons.Filled.QueueMusic, "Playlist", {
                            if ((player?.mediaItemCount ?: 0) > 1) showPlayerMessage("Use previous/next to navigate this playlist.")
                            else showPlayerMessage("No playlist is attached to this stream.")
                            revealControls()
                        }, size = 23)
                        Box {
                            PlayerIconButton(Icons.Filled.MoreVert, "More options", { moreMenuExpanded = true; revealControls() }, size = 25)
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false }
                            ) {
                                DropdownMenuItem(text = { Text("Video quality") }, onClick = {
                                    moreMenuExpanded = false
                                    openTrackDialog(C.TRACK_TYPE_VIDEO, "Video quality")
                                })
                                DropdownMenuItem(text = { Text("Audio tracks") }, onClick = {
                                    moreMenuExpanded = false
                                    openTrackDialog(C.TRACK_TYPE_AUDIO, "Audio tracks")
                                })
                                DropdownMenuItem(text = { Text("Subtitles") }, onClick = {
                                    moreMenuExpanded = false
                                    openTrackDialog(C.TRACK_TYPE_TEXT, "Subtitles", true)
                                })
                                DropdownMenuItem(text = { Text("Cycle playback speed") }, onClick = {
                                    moreMenuExpanded = false
                                    cyclePlaybackSpeed()
                                })
                                DropdownMenuItem(text = { Text("Brightness down") }, onClick = {
                                    moreMenuExpanded = false
                                    brightnessLevel = (brightnessLevel - 0.1f).coerceAtLeast(0.05f)
                                    activity?.window?.attributes = activity.window.attributes.apply { screenBrightness = brightnessLevel }
                                    revealControls()
                                })
                                DropdownMenuItem(text = { Text("Brightness up") }, onClick = {
                                    moreMenuExpanded = false
                                    brightnessLevel = (brightnessLevel + 0.1f).coerceAtMost(1f)
                                    activity?.window?.attributes = activity.window.attributes.apply { screenBrightness = brightnessLevel }
                                    revealControls()
                                })
                                DropdownMenuItem(text = { Text("Volume down") }, onClick = {
                                    moreMenuExpanded = false
                                    val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume - 1).coerceAtLeast(0), 0)
                                    revealControls()
                                })
                                DropdownMenuItem(text = { Text("Volume up") }, onClick = {
                                    moreMenuExpanded = false
                                    val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume + 1).coerceAtMost(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                                    revealControls()
                                })
                                DropdownMenuItem(text = { Text("Cast") }, onClick = {
                                    moreMenuExpanded = false
                                    showPlayerMessage("Casting is not configured in this build.")
                                    revealControls()
                                })
                                DropdownMenuItem(text = { Text("Retry stream") }, onClick = {
                                    moreMenuExpanded = false
                                    playbackError = null
                                    playbackDetails = null
                                    shouldResumePlayback = true
                                    retry++
                                    revealControls()
                                })
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerFloatingIconButton(Icons.Filled.Lock, "Lock screen", {
                            screenLocked = true
                            controlsVisible = false
                        })
                        PlayerFloatingIconButton(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (muted) "Unmute" else "Mute", {
                            muted = !muted
                            revealControls()
                        })
                        PlayerFloatingIconButton(Icons.Filled.Headphones, "Audio tracks", {
                            openTrackDialog(C.TRACK_TYPE_AUDIO, "Audio tracks")
                        })
                        PlayerFloatingButton("${playbackSpeed}×", {
                            cyclePlaybackSpeed()
                        }, description = "Playback speed", fontSize = 15)
                        PlayerFloatingIconButton(Icons.Filled.SkipNext, "Next item", {
                            val active = player
                            if (active != null && active.hasNextMediaItem()) active.seekToNextMediaItem()
                            else showPlayerMessage("There is no next item in this stream.")
                            revealControls()
                        })
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                        .padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (player?.isCurrentMediaItemSeekable == true && durationMs > 0L) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            val shownPosition = (scrubPositionMs ?: positionMs).coerceIn(0L, durationMs)
                            Text(formatPlayerTime(shownPosition), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = shownPosition.toFloat(),
                                onValueChange = {
                                    scrubPositionMs = it.toLong()
                                    revealControls()
                                },
                                onValueChangeFinished = {
                                    scrubPositionMs?.let { target ->
                                        val safeTarget = target.coerceIn(0L, durationMs)
                                        player?.seekTo(safeTarget)
                                        positionMs = safeTarget
                                    }
                                    scrubPositionMs = null
                                    revealControls()
                                },
                                valueRange = 0f..durationMs.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color(0x77FFFFFF)
                                ),
                                modifier = Modifier.weight(1f).height(28.dp)
                            )
                            Text("-${formatPlayerTime((durationMs - shownPosition).coerceAtLeast(0L))}", color = Color.White, fontSize = 12.sp)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (player?.isCurrentMediaItemLive == true) "● LIVE STREAM" else "SEEKING UNAVAILABLE",
                                color = if (player?.isCurrentMediaItemLive == true) Color(0xFFFF7777) else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        PlayerIconButton(Icons.Filled.Lock, "Lock controls", {
                            screenLocked = true
                            controlsVisible = false
                        }, size = 22)
                        PlayerIconButton(Icons.Filled.Replay10, "Rewind 10 seconds", {
                            player?.let { if (it.isCurrentMediaItemSeekable) it.seekBack() }
                            revealControls()
                        }, size = 28, enabled = player?.isCurrentMediaItemSeekable == true)
                        PlayerIconButton(Icons.Filled.SkipPrevious, "Previous item", {
                            player?.let { active -> if (active.hasPreviousMediaItem()) active.seekToPreviousMediaItem() }
                            revealControls()
                        }, size = 26, enabled = player?.hasPreviousMediaItem() == true)
                        Box(
                            Modifier.size(50.dp).border(1.dp, Color.White.copy(alpha = 0.92f), CircleShape)
                                .clickable {
                                    player?.let { active ->
                                        if (active.isPlaying) active.pause()
                                        else {
                                            playbackError = null
                                            playbackDetails = null
                                            shouldResumePlayback = true
                                            active.play()
                                        }
                                    } ?: run {
                                        playbackError = null
                                        shouldResumePlayback = true
                                        retry++
                                    }
                                    revealControls()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        PlayerIconButton(Icons.Filled.SkipNext, "Next item", {
                            player?.let { active -> if (active.hasNextMediaItem()) active.seekToNextMediaItem() }
                            revealControls()
                        }, size = 26, enabled = player?.hasNextMediaItem() == true)
                        PlayerIconButton(Icons.Filled.Forward10, "Forward 10 seconds", {
                            player?.let { if (it.isCurrentMediaItemSeekable) it.seekForward() }
                            revealControls()
                        }, size = 28, enabled = player?.isCurrentMediaItemSeekable == true)
                        PlayerOverlayButton("Speed", { cyclePlaybackSpeed() }, fontSize = 12)
                        PlayerIconButton(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, if (fullscreen) "Exit fullscreen" else "Enter fullscreen", {
                            fullscreenOverride = !fullscreen
                            revealControls()
                        }, size = 25)
                    }
                        }
                    }
                }
            }
        }
        if (!fullscreen) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PlayerOverlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 22,
    enabled: Boolean = true
) {
    Box(
        modifier.sizeIn(minWidth = 32.dp, minHeight = 40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = fontSize.sp,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 24,
    enabled: Boolean = true
) {
    Box(
        modifier.sizeIn(minWidth = 32.dp, minHeight = 40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(size.dp)
        )
    }
}

@Composable
private fun PlayerFloatingIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Int = 23
) {
    Box(
        Modifier.size(46.dp).clip(CircleShape)
            .background(Color(0x99080A0F))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun PlayerFloatingButton(
    label: String,
    onClick: () -> Unit,
    description: String,
    fontSize: Int = 22
) {
    Box(
        Modifier.size(46.dp).clip(CircleShape)
            .background(Color(0x99080A0F))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = fontSize.sp, maxLines = 1, fontWeight = FontWeight.Medium)
    }
}

private data class PlaybackFailure(val message: String, val details: String)

private fun describePlaybackFailure(error: PlaybackException): PlaybackFailure {
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && causes.size < 12) {
        causes += current
        current = current.cause
    }
    val httpError = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    val causeText = causes.drop(1).firstOrNull()?.let {
        "${it.javaClass.simpleName}: ${it.message ?: "No message"}"
    }
    val details = buildString {
        append("${error.errorCodeName} (${error.errorCode})")
        if (httpError != null) append(" • HTTP ${httpError.responseCode}")
        if (causeText != null) append("\n${causeText}")
    }
    val message = when {
        httpError?.responseCode == 401 || httpError?.responseCode == 403 ->
            "The stream provider denied access. The channel may require authorization."
        httpError?.responseCode == 404 ->
            "The provider could not find this stream (HTTP 404)."
        httpError?.responseCode == 429 ->
            "The stream provider is temporarily rate-limiting requests. Try again later."
        httpError != null ->
            "The stream server returned HTTP ${httpError.responseCode}."
        causes.any { it.message?.contains("CLEARTEXT", ignoreCase = true) == true } ->
            "Android blocked HTTP for this channel's host under the app's network security policy."
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Could not connect to the stream server. Check the connection and try again."
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "The stream data could not be parsed. Its format may be unsupported or the server response may be invalid."
        else -> "Playback failed. See the technical details for the Media3 error."
    }
    return PlaybackFailure(message, details)
}

@Composable private fun LibraryScreen() { LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) { item { Text("Your Library", fontSize=30.sp, fontWeight=FontWeight.ExtraBold); Text("A home for your saved and watched titles", color=Muted) }; item { EmptySection("Favorites", "Saved TV channels are available in Live TV. Movie and series favorites require catalogue support.") }; item { EmptySection("Watch history", "Viewing history is not recorded in this phase.") }; item { EmptySection("Continue watching", "Playback progress is not persisted in Phase 2.") } } }

private fun activeProfileIdForSettings(context: Context): String = NexvioProfileStore.active(context).id

private fun settingsPrefs(context: Context): android.content.SharedPreferences {
    val profile = NexvioProfileStore.active(context)
    val name = if (profile.isPrimary) "nexvio_settings" else "nexvio_settings_profile_${profile.id}"
    return context.getSharedPreferences(name, Context.MODE_PRIVATE)
}

@Composable private fun LayoutSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { settingsPrefs(context) }
    var homeStyle by remember { mutableStateOf(prefs.getString("home_style", "Featured") ?: "Featured") }
    var posterSize by remember { mutableStateOf(prefs.getString("poster_size", "Comfortable") ?: "Comfortable") }
    var showDescriptions by remember { mutableStateOf(prefs.getBoolean("show_descriptions", true)) }
    SettingsPage("Layout", "Personalize how content is presented.", onBack = onBack) {
        SettingsChoice("Home screen style", "Choose the default Home presentation.", listOf("Featured", "Grid", "Compact"), homeStyle) {
            homeStyle = it; prefs.edit().putString("home_style", it).apply()
        }
        SettingsChoice("Poster size", "Adjust poster density across catalogue screens.", listOf("Compact", "Comfortable", "Large"), posterSize) {
            posterSize = it; prefs.edit().putString("poster_size", it).apply()
        }
        SettingsToggle("Show content descriptions", "Display extra descriptions where available.", showDescriptions) {
            showDescriptions = it; prefs.edit().putBoolean("show_descriptions", it).apply()
        }
        Text("Preferences are saved on this device. Some layout choices will apply as the corresponding catalogue layouts are connected.", color=Muted, fontSize=12.sp)
    }
}

@Composable private fun PlaybackSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { settingsPrefs(context) }
    var autoplay by remember { mutableStateOf(prefs.getBoolean("playback_autoplay", true)) }
    var resume by remember { mutableStateOf(prefs.getBoolean("playback_resume", true)) }
    var fit by remember { mutableStateOf(prefs.getString("playback_resize", "Fit") ?: "Fit") }
    SettingsPage("Playback", "Choose your preferred playback behavior.", onBack = { /* handled by system back */ }) {
        SettingsToggle("Autoplay streams", "Allow playback to start automatically when a stream is selected.", autoplay) {
            autoplay = it; prefs.edit().putBoolean("playback_autoplay", it).apply()
        }
        SettingsToggle("Resume playback", "Remember the player's play/pause state during player recreation.", resume) {
            resume = it; prefs.edit().putBoolean("playback_resume", it).apply()
        }
        SettingsChoice("Video sizing", "Default display sizing preference.", listOf("Fit", "Fill", "Zoom"), fit) {
            fit = it; prefs.edit().putString("playback_resize", it).apply()
        }
        Text("These preferences are saved. Applying them to every provider and player path is a follow-up integration step.", color=Muted, fontSize=12.sp)
    }
}

@Composable private fun IntegrationsSettingsScreen(onBack: () -> Unit) {
    SettingsPage("Integrations", "Connect external services when supported by Nexvio.", onBack = { /* handled by system back */ }) {
        SettingsInfoCard("TMDB catalogue", "Used for movie and series metadata. API connection is managed by the catalogue feature.")
        SettingsInfoCard("IPTV playlists", "Import an M3U/M3U8 playlist from the Live TV section.")
        SettingsInfoCard("Provider add-ons", "Manage installed add-ons and plugin repositories under Content & Discovery.")
        SettingsInfoCard("Account sync", "Cloud account synchronization is not configured in this build.")
    }
}

@Composable private fun NotificationsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var message by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) postNexvioTestNotification(context) else message = "Notification permission was not granted. You can enable it in Android app settings."
    }
    SettingsPage("Notifications", "Manage episode alerts and verify that notifications can appear on this device.", onBack = { /* handled by system back */ }) {
        SettingsInfoCard("Episode release alerts", "Release alerts need a connected tracking provider and schedule service before real episode alerts can be sent.")
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                postNexvioTestNotification(context)
                message = "Test notification sent."
            }
        }, colors=ButtonDefaults.buttonColors(containerColor=Accent, contentColor=Color.White)) {
            Text("Send test notification")
        }
        message?.let { Text(it, color=Muted, fontSize=12.sp) }
        if (activity == null) Text("Open this screen from the Nexvio app to test notifications.", color=Muted, fontSize=12.sp)
    }
}

private fun postNexvioTestNotification(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "nexvio_general"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(NotificationChannel(channelId, "Nexvio notifications", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Nexvio notifications are working")
        .setContentText("This is a test notification from your settings.")
        .setAutoCancel(true)
        .build()
    manager.notify(4107, notification)
}

@Composable private fun SettingsPage(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("‹  Back", color=Accent, modifier=Modifier.clickable { onBack() })
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize=28.sp, fontWeight=FontWeight.ExtraBold)
        Text(subtitle, color=Muted, fontSize=13.sp)
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement=Arrangement.spacedBy(12.dp), content=content)
    }
}

@Composable private fun SettingsToggle(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color=Muted, fontSize=12.sp, lineHeight=17.sp)
        }
        Switch(checked=checked, onCheckedChange=onCheckedChange)
    }
}

@Composable private fun SettingsChoice(title: String, description: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(title, fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(description, color=Muted, fontSize=12.sp, lineHeight=17.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            choices.forEach { choice ->
                FilterChip(selected=selected == choice, onClick={ onSelect(choice) }, label={ Text(choice, fontSize=11.sp) })
            }
        }
    }
}

@Composable private fun SettingsInfoCard(title: String, description: String) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(title, fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(description, color=Muted, fontSize=12.sp, lineHeight=17.sp)
    }
}

@Composable private fun SettingsScreen(onDetail:(String)->Unit) {
    val sections = listOf(
        "PROFILE" to listOf("Switch Profile" to "Change to a different profile", "Account" to "Account and sync status", "Watch party" to "Watch together with a party code", "Tracking" to "Connect tracking providers and choose Library tracking"),
        "GENERAL" to listOf("Layout" to "Home, streams, collections, and detail pages", "Content & Discovery" to "Manage add-ons and discovery sources", "Playback" to "Player behavior and playback preferences", "Integrations" to "Connect supported services", "Notifications" to "Episode release alerts and test notifications"),
        "ABOUT" to listOf("Supporters & Contributors" to "Project credits and acknowledgements", "Privacy Policy" to "Learn how Nexvio handles your data", "Licenses & Attribution" to "Data sources and open-source licenses", "Update channel" to "Choose stable or beta updates", "Check for updates" to "Check for the latest Nexvio release"),
        "ADVANCED" to listOf("Advanced" to "Startup and profile behavior")
    )
    LazyColumn(contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=28.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("Settings", fontSize=30.sp, fontWeight=FontWeight.ExtraBold)
            Text("Make Nexvio yours", color=Muted)
            Spacer(Modifier.height(8.dp))
        }
        sections.forEach { (section, options) ->
            item {
                Text(section, color=Muted, fontSize=12.sp, fontWeight=FontWeight.Bold, letterSpacing=1.1.sp, modifier=Modifier.padding(top=8.dp,bottom=2.dp))
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp))) {
                    options.forEachIndexed { index, option ->
                        val (title, desc) = option
                        Row(Modifier.fillMaxWidth().clickable { onDetail(title) }.padding(horizontal=16.dp, vertical=15.dp), verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight=FontWeight.SemiBold, color=Color.White)
                                Spacer(Modifier.height(4.dp))
                                Text(desc, color=Muted, fontSize=12.sp, lineHeight=17.sp)
                            }
                            Text("›", color=Accent, fontSize=25.sp)
                        }
                        if (index < options.lastIndex) Divider(color=Color.White.copy(alpha=0.06f))
                    }
                }
            }
        }
    }
}
@Composable private fun InfoPage(title:String) { Column(Modifier.fillMaxWidth().padding(20.dp).background(Panel, RoundedCornerShape(18.dp)).padding(18.dp)) { Text(title, fontSize=23.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text(when(title) { "About Nexvio" -> "Nexvio 1.0.0 • Phase 2\nMovies • Series • Live TV\nAn original Android application."; "Search" -> "Search will become available when a real movie or series catalogue provider is connected."; "Appearance" -> "Nexvio currently uses its premium dark appearance."; "Playback preferences" -> "Live TV uses AndroidX Media3. Supported formats depend on stream and device compatibility."; "Content providers" -> "Movie and series provider integration is planned for a later phase."; "IPTV playlists" -> "Import an M3U/M3U8 file from Live TV or provide an HTTP/HTTPS playlist URL."; "Downloads" -> "Download supported direct media URLs and manage your offline files."; else -> "This feature is planned for a later Nexvio phase." }, color=Muted, lineHeight=23.sp); Spacer(Modifier.height(14.dp)); Text("NEXVIO", color=Accent, fontSize=10.sp, letterSpacing=1.5.sp) } }
