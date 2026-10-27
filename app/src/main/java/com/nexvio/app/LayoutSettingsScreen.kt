package com.nexvio.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

internal object AppAppearanceState {
    var selectedTheme by mutableStateOf("Emerald")
    var amoledBlack by mutableStateOf(false)
    var initialized by mutableStateOf(false)

    val accent: Color
        get() = themeColors.firstOrNull { it.name == selectedTheme }?.color ?: Color(0xFF38C77A)
    val background: Color
        get() = if (amoledBlack) Color.Black else Color(0xFF101114)
}

private data class LayoutTheme(val name: String, val color: Color)
private val themeColors = listOf(
    LayoutTheme("Custom", Color(0xFFFF5C9A)),
    LayoutTheme("White", Color(0xFFF5F5F5)),
    LayoutTheme("Crimson", Color(0xFFE53950)),
    LayoutTheme("Ocean", Color(0xFF3298F5)),
    LayoutTheme("Violet", Color(0xFF9B70F5)),
    LayoutTheme("Emerald", Color(0xFF38C77A)),
    LayoutTheme("Amber", Color(0xFFFFA43A)),
    LayoutTheme("Rose", Color(0xFFE85D9E))
)

private val layoutPanel = Color(0xFF1B1D22)
private val layoutMuted = Color(0xFF9A9DA6)
private fun layoutPrefs(context: Context) = context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE)

@Composable
fun LayoutSettingsScreenV2(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { layoutPrefs(context) }
    LaunchedEffect(prefs) {
        if (!AppAppearanceState.initialized) {
            AppAppearanceState.selectedTheme = prefs.getString("theme_name", "Emerald") ?: "Emerald"
            AppAppearanceState.amoledBlack = prefs.getBoolean("amoled_black", false)
            AppAppearanceState.initialized = true
        }
    }

    var appIconDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var navigationDialog by remember { mutableStateOf(false) }
    var homeDialog by remember { mutableStateOf(false) }
    var collectionsDialog by remember { mutableStateOf(false) }
    var continueDialog by remember { mutableStateOf(false) }
    var posterDialog by remember { mutableStateOf(false) }
    var streamsDialog by remember { mutableStateOf(false) }
    var detailDialog by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    var selectedIcon by remember { mutableStateOf(prefs.getString("app_icon", "Rose Gold") ?: "Rose Gold") }
    var language by remember { mutableStateOf(prefs.getString("app_language", "Device language") ?: "Device language") }
    var navStyle by remember { mutableStateOf(prefs.getString("navigation_style", "Adaptive") ?: "Adaptive") }
    var navGlow by remember { mutableStateOf(prefs.getBoolean("navigation_glow", true)) }
    var homeStyle by remember { mutableStateOf(prefs.getString("home_style", "Featured") ?: "Featured") }
    var posterSize by remember { mutableStateOf(prefs.getString("poster_size", "Comfortable") ?: "Comfortable") }
    var showDescriptions by remember { mutableStateOf(prefs.getBoolean("show_descriptions", true)) }
    var posterWidth by remember { mutableFloatStateOf(prefs.getFloat("poster_card_width", 126f)) }
    var posterRadius by remember { mutableFloatStateOf(prefs.getFloat("poster_corner_radius", 13f)) }
    var continueEnabled by remember { mutableStateOf(prefs.getBoolean("continue_watching_enabled", true)) }
    var continueCompleted by remember { mutableStateOf(prefs.getBoolean("continue_watching_completed", false)) }
    var collectionNames by remember {
        mutableStateOf(runCatching {
            val array = JSONArray(prefs.getString("layout_collections", "[]"))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList()))
    }
    var newCollection by remember { mutableStateOf("") }
    var amoled by remember { mutableStateOf(prefs.getBoolean("amoled_black", false)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppAppearanceState.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 10.dp))
                Spacer(Modifier.width(8.dp))
                Text("Layout", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        item {
            LayoutSectionTitle("THEME")
            Column(Modifier.fillMaxWidth().background(layoutPanel, RoundedCornerShape(20.dp)).padding(14.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(284.dp),
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(themeColors) { theme ->
                        val selected = AppAppearanceState.selectedTheme == theme.name
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (theme.name == "Custom") customDialog = true
                                    else {
                                        AppAppearanceState.selectedTheme = theme.name
                                        prefs.edit().putString("theme_name", theme.name).apply()
                                    }
                                }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier.size(52.dp)
                                    .then(if (selected) Modifier.background(theme.color.copy(alpha = .22f), CircleShape) else Modifier)
                                    .padding(if (selected) 4.dp else 0.dp)
                                    .background(theme.color, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) Text("✓", color = if (theme.name == "White" || theme.name == "Amber") Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (theme.name == "Custom" && !selected) Text("✎", color = Color.White, fontSize = 19.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(theme.name, color = if (selected) theme.color else Color.White.copy(alpha = .78f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp)
                        }
                    }
                }
                Text("Your accent is applied to Nexvio's Material controls and selected navigation state.", color = layoutMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            LayoutSectionTitle("DISPLAY")
            LayoutCard {
                LayoutToggleRow("AMOLED Black", "Use pure black backgrounds for OLED screens.", amoled) {
                    amoled = it
                    AppAppearanceState.amoledBlack = it
                    prefs.edit().putBoolean("amoled_black", it).apply()
                }
                LayoutDivider()
                LayoutActionRow("App Icon", selectedIcon, "Choose the icon shown in Nexvio settings.", onClick = { appIconDialog = true })
                LayoutDivider()
                LayoutActionRow("App Language", language, null, onClick = { languageDialog = true })
                LayoutDivider()
                LayoutActionRow("Navigation Bar", "$navStyle · Glow ${if (navGlow) "on" else "off"}", null, onClick = { navigationDialog = true })
            }
        }
        item {
            LayoutSectionTitle("HOME")
            LayoutCard {
                LayoutActionRow("Home Layout", description = "Adjust home layout, content visibility, and poster behavior", onClick = { homeDialog = true })
                LayoutDivider()
                LayoutActionRow("Collections", description = "Create custom catalog groupings with folders shown on Home", onClick = { collectionsDialog = true })
                LayoutDivider()
                LayoutActionRow("Continue Watching", description = "Settings for the Continue Watching section", onClick = { continueDialog = true })
                LayoutDivider()
                LayoutActionRow("Poster Card Style", description = "Tune card width and corner radius", onClick = { posterDialog = true })
            }
        }
        item {
            LayoutSectionTitle("STREAMS")
            LayoutCard {
                LayoutActionRow("Streams", description = "Stream result display and badge URL rules", onClick = { streamsDialog = true })
            }
        }
        item {
            LayoutSectionTitle("DETAIL PAGE")
            LayoutCard {
                LayoutActionRow("Detail Page", description = "Detail-page options available in this build", onClick = { detailDialog = true })
            }
        }
        item {
            statusMessage?.let { Text(it, color = AppAppearanceState.accent, fontSize = 12.sp) }
        }
    }

    if (customDialog) {
        AlertDialog(
            onDismissRequest = { customDialog = false },
            title = { Text("Custom accent color") },
            text = { Text("A dedicated color picker is not currently part of Nexvio. Choose one of the built-in accent colors instead.") },
            confirmButton = { TextButton(onClick = { AppAppearanceState.selectedTheme = "Rose"; prefs.edit().putString("theme_name", "Rose").apply(); customDialog = false }) { Text("Use Rose") } },
            dismissButton = { TextButton(onClick = { customDialog = false }) { Text("Cancel") } }
        )
    }
    if (appIconDialog) {
        LayoutChoiceDialog("App Icon", listOf("Rose Gold", "Nexvio Blue", "Emerald", "Monochrome"), selectedIcon, { selectedIcon = it; prefs.edit().putString("app_icon", it).apply(); appIconDialog = false; statusMessage = "Saved preference. Android launcher icon switching is not configured in this build." }, { appIconDialog = false }, note = "Icon choice is saved as a preference. Dynamic launcher aliases are not configured, so the device icon will not change.")
    }
    if (languageDialog) {
        LayoutChoiceDialog("App Language", listOf("Device language", "English"), language, { language = it; prefs.edit().putString("app_language", it).apply(); languageDialog = false; statusMessage = if (it == "English") "English selected. Other localized resources are not yet available." else "Device language selected; the interface currently ships with English strings." }, { languageDialog = false }, note = "Only English UI resources are currently included.")
    }
    if (navigationDialog) {
        AlertDialog(
            onDismissRequest = { navigationDialog = false },
            title = { Text("Navigation Bar") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Adaptive", "Compact", "Minimal").forEach { style ->
                    Row(Modifier.fillMaxWidth().clickable { navStyle = style; prefs.edit().putString("navigation_style", style).apply() }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = navStyle == style, onClick = { navStyle = style; prefs.edit().putString("navigation_style", style).apply() })
                        Spacer(Modifier.width(8.dp)); Text(style)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Glow effect", modifier = Modifier.weight(1f))
                    Switch(checked = navGlow, onCheckedChange = { navGlow = it; prefs.edit().putBoolean("navigation_glow", it).apply() })
                }
                Text("These preferences are saved; custom navigation rendering is not yet wired to every route.", color = layoutMuted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { navigationDialog = false }) { Text("Done") } }
        )
    }
    if (homeDialog) {
        AlertDialog(
            onDismissRequest = { homeDialog = false },
            title = { Text("Home Layout") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Home presentation", fontWeight = FontWeight.SemiBold)
                listOf("Featured", "Grid", "Compact").forEach { value ->
                    Row(Modifier.fillMaxWidth().clickable { homeStyle = value; prefs.edit().putString("home_style", value).apply() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = homeStyle == value, onClick = { homeStyle = value; prefs.edit().putString("home_style", value).apply() }); Text(value)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show descriptions", modifier = Modifier.weight(1f))
                    Switch(checked = showDescriptions, onCheckedChange = { showDescriptions = it; prefs.edit().putBoolean("show_descriptions", it).apply() })
                }
                Text("Section reordering and per-section visibility are not supported by the current Home screen.", color = layoutMuted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { homeDialog = false }) { Text("Done") } }
        )
    }
    if (collectionsDialog) {
        AlertDialog(
            onDismissRequest = { collectionsDialog = false },
            title = { Text("Collections") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (collectionNames.isEmpty()) Text("No custom collections yet.", color = layoutMuted)
                collectionNames.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            collectionNames = collectionNames - name
                            prefs.edit().putString("layout_collections", JSONArray(collectionNames).toString()).apply()
                        }) { Text("Remove") }
                    }
                }
                OutlinedTextField(value = newCollection, onValueChange = { newCollection = it }, label = { Text("New collection name") }, singleLine = true)
                Text("Collections are stored locally. Catalog assignment and Home folder rendering are not available in this build yet.", color = layoutMuted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = {
                val name = newCollection.trim()
                if (name.isNotEmpty() && collectionNames.none { it.equals(name, true) }) {
                    collectionNames = collectionNames + name
                    prefs.edit().putString("layout_collections", JSONArray(collectionNames).toString()).apply()
                    newCollection = ""
                }
                collectionsDialog = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { collectionsDialog = false }) { Text("Cancel") } }
        )
    }
    if (continueDialog) {
        AlertDialog(
            onDismissRequest = { continueDialog = false },
            title = { Text("Continue Watching") },
            text = { Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Show section", modifier = Modifier.weight(1f)); Switch(checked = continueEnabled, onCheckedChange = { continueEnabled = it; prefs.edit().putBoolean("continue_watching_enabled", it).apply() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Include completed titles", modifier = Modifier.weight(1f)); Switch(checked = continueCompleted, onCheckedChange = { continueCompleted = it; prefs.edit().putBoolean("continue_watching_completed", it).apply() }) }
                Text("Continue Watching reads the existing watch-history store; these display filters are saved but not yet applied to Home.", color = layoutMuted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { continueDialog = false }) { Text("Done") } }
        )
    }
    if (posterDialog) {
        AlertDialog(
            onDismissRequest = { posterDialog = false },
            title = { Text("Poster Card Style") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Card width: ${posterWidth.toInt()} dp")
                Slider(value = posterWidth, onValueChange = { posterWidth = it; prefs.edit().putFloat("poster_card_width", it).apply() }, valueRange = 90f..180f)
                Text("Corner radius: ${posterRadius.toInt()} dp")
                Slider(value = posterRadius, onValueChange = { posterRadius = it; prefs.edit().putFloat("poster_corner_radius", it).apply() }, valueRange = 0f..28f)
                Text("Values are saved; existing poster components still use their current dimensions until wired to these preferences.", color = layoutMuted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { posterDialog = false }) { Text("Done") } }
        )
    }
    if (streamsDialog) {
        AlertDialog(onDismissRequest = { streamsDialog = false }, title = { Text("Streams") }, text = { Text("Stream selection is currently handled by Nexvio's existing provider result flow. Advanced badge URL and result-display overrides are not exposed by this build, so stream-search behavior remains unchanged.") }, confirmButton = { TextButton(onClick = { streamsDialog = false }) { Text("Done") } })
    }
    if (detailDialog) {
        AlertDialog(onDismissRequest = { detailDialog = false }, title = { Text("Detail Page") }, text = { Text("Nexvio currently uses the existing title detail layout. No additional detail-page preferences are defined in the current implementation.") }, confirmButton = { TextButton(onClick = { detailDialog = false }) { Text("Done") } })
    }
}

@Composable
private fun LayoutSectionTitle(title: String) {
    Text(title, color = layoutMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun LayoutCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(layoutPanel, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp), content = content)
}

@Composable
private fun LayoutDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = .065f), thickness = 1.dp)
}

@Composable
private fun LayoutToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(description, color = layoutMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LayoutActionRow(title: String, value: String? = null, description: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(description, color = layoutMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (!value.isNullOrBlank()) Text(value, color = layoutMuted, fontSize = 12.sp)
            Text("›", color = AppAppearanceState.accent, fontSize = 24.sp)
        }
    }
}

@Composable
private fun LayoutChoiceDialog(title: String, choices: List<String>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit, note: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column {
            choices.forEach { value ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == value, onClick = { onSelect(value) })
                    Spacer(Modifier.width(8.dp)); Text(value)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(note, color = layoutMuted, fontSize = 12.sp, lineHeight = 17.sp)
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
