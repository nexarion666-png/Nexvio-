package com.nexvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ContentProvidersScreen(
    onOpenAddons: () -> Unit,
    onOpenRepositories: () -> Unit,
    onBack: () -> Unit
) {
    val accent = AppAppearanceState.accent
    val muted = AppAppearanceState.muted
    Column(Modifier.fillMaxSize().background(AppAppearanceState.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("‹  Back", color = accent, modifier = Modifier.clickable(onClick = onBack))
        }
        Text("Content providers", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Manage installed add-ons or register a plugin repository.", color = muted, fontSize = 14.sp)
        Surface(
            color = AppAppearanceState.panel,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAddons)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Add-ons", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Enter a provider manifest URL, validate and install add-ons, and manage installed providers.", color = muted, fontSize = 13.sp)
                Text("Open add-ons  ›", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        Surface(
            color = AppAppearanceState.panel,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRepositories)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Plugin repositories", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Add and manage HTTPS plugin repository manifests.", color = muted, fontSize = 13.sp)
                Text("Open repositories  ›", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}
