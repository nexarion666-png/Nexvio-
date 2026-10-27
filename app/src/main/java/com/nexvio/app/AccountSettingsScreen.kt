package com.nexvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext

private val AccountBg = Color(0xFF0C0E0D)
private val AccountCard = Color(0xFF191B1A)
private val AccountGreen = Color(0xFF55B85B)
private val AccountMuted = Color(0xFF999D9A)

@Composable
internal fun AccountSettingsScreen(
    onBack: () -> Unit,
    onSwitchProfile: () -> Unit
) {
    val context = LocalContext.current
    val profile = remember { NexvioProfileStore.active(context) }
    val avatar = NexvioAvatars.firstOrNull { it.id == profile.avatarId } ?: NexvioAvatars.first()

    Column(
        Modifier.fillMaxSize()
            .background(AccountBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("‹  Back", color = AccountGreen, fontSize = 16.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(vertical = 8.dp, horizontal = 4.dp))
        Spacer(Modifier.height(12.dp))
        Text("Account", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(5.dp))
        Text("Your Nexvio profile and account status", color = AccountMuted, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(AccountCard).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color(avatar.color)),
                contentAlignment = Alignment.Center
            ) {
                Text(avatar.emoji, fontSize = 36.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(if (profile.isPrimary) "Primary profile" else "Standard profile", color = AccountGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("This device", color = AccountMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("ACCOUNT STATUS", color = AccountMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
        AccountStatusRow(
            icon = { Icon(Icons.Default.Person, contentDescription = null, tint = AccountGreen) },
            title = "Nexvio account",
            value = "Local profile",
            detail = "Your profile is stored on this device. Online sign-in is not configured in this build."
        )
        Spacer(Modifier.height(2.dp))
        AccountStatusRow(
            icon = { Icon(Icons.Default.CloudOff, contentDescription = null, tint = AccountGreen) },
            title = "Cloud sync",
            value = "Not connected",
            detail = "Cloud backup and cross-device synchronization are not available yet."
        )
        Spacer(Modifier.height(2.dp))
        AccountStatusRow(
            icon = { Icon(Icons.Default.Devices, contentDescription = null, tint = AccountGreen) },
            title = "Personal data",
            value = "Profile-specific",
            detail = "Library, downloads, watch history, Continue Watching, and supported preferences belong to this profile."
        )
        Spacer(Modifier.height(2.dp))
        AccountStatusRow(
            icon = { Icon(Icons.Default.Sync, contentDescription = null, tint = AccountGreen) },
            title = "Add-ons and plugins",
            value = "Shared across profiles",
            detail = "Installed providers and plugin configuration remain shared on this installation."
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSwitchProfile,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccountGreen, contentColor = Color(0xFF071008))
        ) {
            Icon(Icons.Default.Group, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Switch or manage profiles", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Signing in and cloud sync will only appear here when Nexvio has a supported account service. No online account is simulated.",
            color = AccountMuted, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun AccountStatusRow(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    detail: String
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AccountCard).padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF203023)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(value, color = AccountGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Text(detail, color = AccountMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
