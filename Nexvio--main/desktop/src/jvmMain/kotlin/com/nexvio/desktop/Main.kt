package com.nexvio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
            Spacer(Modifier.height(28.dp))
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
