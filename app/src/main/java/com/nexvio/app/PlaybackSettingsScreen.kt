package com.nexvio.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val playbackPanel = Color(0xFF1B1D22)
private val playbackMuted = Color(0xFF9A9DA6)
private fun playbackPrefs(context: Context) = context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE)

@Composable
fun PlaybackSettingsScreenV2(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { playbackPrefs(context) }
    var autoplay by remember { mutableStateOf(prefs.getBoolean("playback_autoplay", true)) }
    var resume by remember { mutableStateOf(prefs.getBoolean("playback_resume", true)) }
    var resize by remember { mutableStateOf(prefs.getString("playback_resize", "Fit") ?: "Fit") }
    var loadingOverlay by remember { mutableStateOf(prefs.getBoolean("playback_loading_overlay", true)) }
    var minBuffer by remember { mutableFloatStateOf(prefs.getInt("playback_min_buffer", 15).toFloat()) }
    var maxBuffer by remember { mutableFloatStateOf(prefs.getInt("playback_max_buffer", 45).toFloat()) }
    var startBuffer by remember { mutableFloatStateOf(prefs.getInt("playback_start_buffer", 5).toFloat()) }
    var backBuffer by remember { mutableFloatStateOf(prefs.getInt("playback_back_buffer", 10).toFloat()) }
    var preferredAudio by remember { mutableStateOf(prefs.getString("preferred_audio_language", "Device language") ?: "Device language") }
    var preferredSubtitle by remember { mutableStateOf(prefs.getString("preferred_subtitle_language", "Device language") ?: "Device language") }
    var autoSubtitles by remember { mutableStateOf(prefs.getBoolean("auto_subtitles", true)) }
    var subtitleSize by remember { mutableFloatStateOf(prefs.getFloat("subtitle_size_sp", 18f)) }
    var autoplayNext by remember { mutableStateOf(prefs.getBoolean("playback_autoplay_next", false)) }
    var touchGestures by remember { mutableStateOf(prefs.getBoolean("playback_touch_gestures", false)) }
    var dialog by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppAppearanceState.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 10.dp))
                Text("Playback", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("Configure supported player behavior. Options that need a missing engine or service are identified below.", color = playbackMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
        item {
            PlaybackSection("PLAYER") {
                PlaybackToggle("Autoplay streams", "Start a selected stream when the player opens.", autoplay) {
                    autoplay = it; prefs.edit().putBoolean("playback_autoplay", it).apply()
                }
                PlaybackDivider()
                PlaybackToggle("Resume playback", "Restore the last saved position for seekable on-demand media.", resume) {
                    resume = it; prefs.edit().putBoolean("playback_resume", it).apply()
                }
                PlaybackDivider()
                PlaybackToggle("Loading overlay", "Show the loading indicator while Media3 prepares or buffers a stream.", loadingOverlay) {
                    loadingOverlay = it; prefs.edit().putBoolean("playback_loading_overlay", it).apply()
                }
                PlaybackDivider()
                PlaybackSelect("Video sizing", resize, onClick = { dialog = "Video sizing" })
                PlaybackDivider()
                PlaybackToggle("Touch gestures", "Gesture seeking and swipe volume/brightness controls are not implemented yet; this preference is saved but has no player effect.", touchGestures, enabled = false) { touchGestures = it }
                PlaybackDivider()
                PlaybackUnavailable("Player engine", "AndroidX Media3 ExoPlayer is the only integrated engine in this build. libmpv is unavailable.")
                PlaybackUnavailable("Hold to speed / Hold speed", "Press-and-hold playback-speed gestures are not implemented.")
            }
        }
        item {
            PlaybackSection("BUFFERING AND STREAM PERFORMANCE") {
                PlaybackSlider("Min Buffer", "Target minimum buffered media duration", minBuffer, 5f..60f, "${minBuffer.toInt()} s") {
                    minBuffer = it
                    if (minBuffer > maxBuffer) maxBuffer = minBuffer
                    prefs.edit().putInt("playback_min_buffer", minBuffer.toInt()).putInt("playback_max_buffer", maxBuffer.toInt()).apply()
                }
                PlaybackDivider()
                PlaybackSlider("Max Buffer", "Upper bound for Media3's playback buffer", maxBuffer, 15f..120f, "${maxBuffer.toInt()} s") {
                    maxBuffer = it
                    if (maxBuffer < minBuffer) minBuffer = maxBuffer
                    prefs.edit().putInt("playback_min_buffer", minBuffer.toInt()).putInt("playback_max_buffer", maxBuffer.toInt()).apply()
                }
                PlaybackDivider()
                PlaybackSlider("Start Buffer", "Preferred buffered duration before playback starts", startBuffer, 1f..30f, "${startBuffer.toInt()} s") {
                    startBuffer = it; prefs.edit().putInt("playback_start_buffer", it.toInt()).apply()
                }
                PlaybackDivider()
                PlaybackSlider("Back Buffer", "Retain previously played media where the source supports it", backBuffer, 0f..60f, "${backBuffer.toInt()} s") {
                    backBuffer = it; prefs.edit().putInt("playback_back_buffer", it.toInt()).apply()
                }
                PlaybackDivider()
                PlaybackUnavailable("Buffer after stall / Target buffer size", "Media3 load-control configuration is not yet connected to these stored preferences.")
                PlaybackUnavailable("VOD disk cache", "No shared Media3 disk cache or cache-management UI is configured yet.")
            }
        }
        item {
            PlaybackSection("SUBTITLE AND AUDIO") {
                PlaybackSelect("Preferred audio language", preferredAudio, onClick = { dialog = "Preferred audio language" })
                PlaybackDivider()
                PlaybackSelect("Preferred subtitle language", preferredSubtitle, onClick = { dialog = "Preferred subtitle language" })
                PlaybackDivider()
                PlaybackToggle("Automatic subtitles", "When a movie or episode starts, find and apply the best subtitle in your preferred language.", autoSubtitles) {
                    autoSubtitles = it; prefs.edit().putBoolean("auto_subtitles", it).apply()
                }
                PlaybackDivider()
                PlaybackUnavailable("Secondary languages / forced subtitles / SDH filtering", "Track preference rules are not wired to Media3 track-selection parameters yet.")
                PlaybackUnavailable("Subtitle timing sync", "Automatic subtitle timing correction is not available in the current player.")
            }
        }
        item {
            PlaybackSection("SUBTITLE RENDERING") {
                PlaybackSlider("Subtitle size", "Preferred subtitle text size", subtitleSize, 12f..36f, "${subtitleSize.toInt()} sp") {
                    subtitleSize = it; prefs.edit().putFloat("subtitle_size_sp", it).apply()
                }
                PlaybackDivider()
                PlaybackUnavailable("Text/background/outline colors, bold, libass, render resolution", "Subtitle styling preferences are not connected to the active Media3 SubtitleView. libass is not integrated.")
            }
        }
        item {
            PlaybackSection("STREAM SELECTION") {
                PlaybackUnavailable("Reuse last link / Auto stream selection / timeout", "Nexvio's existing stream-result flow is intentionally unchanged. Automatic source selection is not implemented here.")
                PlaybackUnavailable("Allowed add-ons and plugins", "These controls require the existing provider/plugin registry to expose selection rules.")
            }
        }
        item {
            PlaybackSection("SKIP SEGMENTS") {
                PlaybackUnavailable("Skip intro / AnimeSkip / automatic skipping", "No intro timestamp service is configured. Ordinary playback remains available.")
            }
        }
        item {
            PlaybackSection("NEXT EPISODE") {
                PlaybackToggle("Auto-play next episode", "Preference only; the current player handles live TV and has no episode progression manager.", autoplayNext, enabled = false) {
                    autoplayNext = it; prefs.edit().putBoolean("playback_autoplay_next", it).apply()
                }
                PlaybackUnavailable("Episode thresholds / binge group / preload sources", "Series episode progression and source preloading are not implemented in this build.")
            }
        }
        item {
            PlaybackSection("DECODER AND VIDEO RENDERING") {
                PlaybackUnavailable("libmpv renderer / hardware decoding / YUV420P", "libmpv is not integrated.")
                PlaybackUnavailable("Decoder priority / DV7 fallback / tunneled playback", "These capabilities are not exposed by the current Nexvio player implementation.")
            }
        }
        item {
            PlaybackSection("P2P STREAMING") {
                PlaybackUnavailable("P2P streaming / torrent cache", "No torrent or peer-to-peer engine is integrated. No cache files will be created or removed by these settings.")
            }
        }
    }

    when (dialog) {
        "Video sizing" -> PlaybackChoiceDialog(dialog!!, listOf("Fit", "Fill", "Zoom"), resize, {
            resize = it; prefs.edit().putString("playback_resize", it).apply(); dialog = null
        }) { dialog = null }
        "Preferred audio language" -> PlaybackChoiceDialog(dialog!!, listOf("Device language", "No preference", "English", "Japanese", "Spanish", "French"), preferredAudio, {
            preferredAudio = it; prefs.edit().putString("preferred_audio_language", it).apply(); dialog = null
        }) { dialog = null }
        "Preferred subtitle language" -> PlaybackChoiceDialog(dialog!!, listOf("Device language", "No subtitles", "English", "Japanese", "Spanish", "French"), preferredSubtitle, {
            preferredSubtitle = it; prefs.edit().putString("preferred_subtitle_language", it).apply(); dialog = null
        }) { dialog = null }
    }
}

@Composable
private fun PlaybackSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, color = playbackMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().background(playbackPanel, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp), content = content)
    }
}

@Composable
private fun PlaybackToggle(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = if (enabled) Color.White else playbackMuted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(description, color = playbackMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun PlaybackSelect(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value + "  ›", color = AppAppearanceState.accent, fontSize = 12.sp)
    }
}

@Composable
private fun PlaybackUnavailable(title: String, reason: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Text(title, color = playbackMuted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(reason, color = playbackMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PlaybackSlider(title: String, description: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, color = AppAppearanceState.accent, fontSize = 12.sp)
        }
        Text(description, color = playbackMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = AppAppearanceState.accent, activeTrackColor = AppAppearanceState.accent))
    }
}

@Composable
private fun PlaybackDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = .065f), thickness = 1.dp)
}

@Composable
private fun PlaybackChoiceDialog(title: String, choices: List<String>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column {
            choices.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == option, onClick = { onSelect(option) })
                    Spacer(Modifier.width(8.dp)); Text(option)
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
