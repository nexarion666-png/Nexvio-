package com.nexvio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nexvio.app.M3uParser
import com.nexvio.app.TvChannel
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val Background = Color(0xFF0B0D12)
private val Sidebar = Color(0xFF10141D)
private val Panel = Color(0xFF171D29)
private val Accent = Color(0xFF7C5CFC)
private val Muted = Color(0xFF9AA4B2)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nexvio",
        state = rememberWindowState(width = 1360.dp, height = 850.dp)
    ) {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = Accent,
            background = Background,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White
        )) {
            NexvioDesktopApp()
        }
    }
}

@Composable
private fun NexvioDesktopApp() {
    val destinations = listOf("Home", "Movies", "Series", "Live TV", "Add-ons", "Downloads", "Settings")
    var selected by remember { mutableStateOf("Home") }
    var search by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var playlistMessage by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize().background(Background)) {
        Column(
            modifier = Modifier.width(238.dp).fillMaxHeight().background(Sidebar).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("NEXVIO", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            Text("ENTERTAINMENT", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            destinations.forEach { destination ->
                val active = selected == destination
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) Color(0xFF25213D) else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { selected = destination }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(destination, color = if (active) Color.White else Muted,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Nexvio for Windows", color = Muted, fontSize = 11.sp)
            Text("Native desktop edition", color = Muted, fontSize = 10.sp)
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Your entertainment, in one place.", color = Muted, fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.width(300.dp),
                    singleLine = true,
                    placeholder = { Text("Search Nexvio") },
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Spacer(Modifier.height(24.dp))

            if (selected == "Live TV") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("IMPORT AN IPTV PLAYLIST", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Load an M3U or M3U8 playlist from your PC.", color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold)
                        Text("Nexvio reads channel names, groups, logos and stream URLs from the playlist.",
                            color = Muted, fontSize = 13.sp)
                        Button(onClick = {
                            val dialog = FileDialog(null as Frame?, "Select an M3U playlist", FileDialog.LOAD)
                            dialog.isVisible = true
                            val selectedFile = if (dialog.file != null && dialog.directory != null) {
                                File(dialog.directory, dialog.file)
                            } else null
                            if (selectedFile != null) {
                                val parsed = runCatching { M3uParser.parse(selectedFile.readText()) }
                                    .getOrElse { error ->
                                        playlistMessage = error.message ?: "Could not read that playlist."
                                        null
                                    }
                                if (parsed != null) {
                                    channels = parsed.channels
                                    playlistMessage = parsed.error ?: "Loaded ${parsed.channels.size} channels."
                                }
                            }
                        }) {
                            Text("Choose playlist file")
                        }
                        playlistMessage?.let { Text(it, color = if (channels.isEmpty()) Muted else Color(0xFF9FE3B0), fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("CHANNELS · ${channels.size}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (channels.isEmpty()) {
                    Text("No playlist loaded yet.", color = Muted, fontSize = 13.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(channels, key = { it.id }) { channel ->
                            Card(colors = CardDefaults.cardColors(containerColor = Panel),
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(channel.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(channel.category ?: "Uncategorized", color = Muted, fontSize = 11.sp)
                                    }
                                    Text("STREAM", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("THE NEXVIO WINDOWS EDITION", color = Accent, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold)
                        Text("Built for Windows.\nPowered by Nexvio.", color = Color.White,
                            fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "This is the native Kotlin desktop application—not the website in an app window.",
                            color = Muted, fontSize = 14.sp
                        )
                        Surface(color = Color(0xFF28213F), shape = RoundedCornerShape(30.dp)) {
                            Text("FOUNDATION BUILD · FEATURES BEING PORTED", color = Color(0xFFD5C9FF),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("PORTING STATUS", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatusCard("Native Windows shell", "Implemented", "Kotlin + Compose Desktop", Modifier.weight(1f))
                    StatusCard("Android feature port", "In progress", "Playback, add-ons, IPTV and sync", Modifier.weight(1f))
                    StatusCard("Windows installer", "CI configured", "MSI and EXE packaging", Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "The Android app remains unchanged. Windows-specific playback, storage, networking and account flows " +
                        "will be ported and tested before being presented as available.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, status: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.heightIn(min = 124.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(status, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Muted, fontSize = 11.sp)
        }
    }
}
