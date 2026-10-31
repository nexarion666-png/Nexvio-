package com.nexvio.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ProfileBg = Color(0xFF090D12)
private val ProfileCard = Color(0xFF171D23)
private val ProfileField = Color(0xFF172820)
private val ProfileGreen = Color(0xFFB7F36B)
private val ProfileMuted = Color(0xFF9AA4AE)

internal data class NexvioProfile(
    val id: String,
    val name: String,
    val avatarId: String,
    val customAvatarUrl: String = "",
    val isPrimary: Boolean = false,
    val usePrimaryAddons: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

internal data class NexvioAvatar(val id: String, val name: String, val emoji: String, val color: Long)

internal val NexvioAvatars = listOf(
    NexvioAvatar("fox","Fox","🦊",0xFFB65D3D), NexvioAvatar("bear","Bear","🐻",0xFF916548),
    NexvioAvatar("cat","Cat","🐱",0xFF9B83C7), NexvioAvatar("owl","Owl","🦉",0xFF497F9B),
    NexvioAvatar("tiger","Tiger","🐯",0xFFE18A32), NexvioAvatar("panda","Panda","🐼",0xFF6B777E),
    NexvioAvatar("rabbit","Rabbit","🐰",0xFFD98AA5), NexvioAvatar("frog","Frog","🐸",0xFF4B9B68),
    NexvioAvatar("lion","Lion","🦁",0xFFD5A344), NexvioAvatar("koala","Koala","🐨",0xFF7D929A),
    NexvioAvatar("pig","Pig","🐷",0xFFE08C9D), NexvioAvatar("cow","Cow","🐮",0xFF6E9CA0),
    NexvioAvatar("unicorn","Unicorn","🦄",0xFF9972D2), NexvioAvatar("dragon","Dragon","🐲",0xFF4D987E),
    NexvioAvatar("penguin","Penguin","🐧",0xFF527DAA), NexvioAvatar("monkey","Monkey","🐵",0xFFB17B4B),
    NexvioAvatar("dog","Dog","🐶",0xFFC18B5A), NexvioAvatar("deer","Deer","🦌",0xFF9B7958),
    NexvioAvatar("butterfly","Butterfly","🦋",0xFF7669C5), NexvioAvatar("star","Star","⭐",0xFFD7AC47),
    NexvioAvatar("robot","Robot","🤖",0xFF638A9D), NexvioAvatar("alien","Alien","👽",0xFF5D9A66),
    NexvioAvatar("astronaut","Astronaut","🧑‍🚀",0xFF5674A6), NexvioAvatar("artist","Artist","🎨",0xFFB86F9C)
)

internal object NexvioProfileStore {
    private const val PREFS = "nexvio_profiles"
    private const val PROFILES = "profiles"
    private const val ACTIVE = "active_profile_id"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun all(context: Context): List<NexvioProfile> {
        val p = prefs(context)
        val parsed = runCatching {
            val array = JSONArray(p.getString(PROFILES, "[]") ?: "[]")
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = o.optString("name").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                NexvioProfile(id, name, o.optString("avatar", "fox"), o.optString("customAvatar", ""),
                    o.optBoolean("primary", false), o.optBoolean("primaryAddons", true),
                    o.optLong("createdAt", System.currentTimeMillis()), o.optLong("updatedAt", System.currentTimeMillis()))
            }
        }.getOrDefault(emptyList())
        // Do not silently create a profile. First-time users must create their own.
        // Existing saved profiles are preserved exactly as they were.
        return parsed
    }

    @Synchronized fun save(context: Context, profiles: List<NexvioProfile>) {
        val array = JSONArray()
        profiles.forEach { p -> array.put(JSONObject()
            .put("id", p.id).put("name", p.name).put("avatar", p.avatarId)
            .put("customAvatar", p.customAvatarUrl).put("primary", p.isPrimary)
            .put("primaryAddons", p.usePrimaryAddons).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)) }
        prefs(context).edit().putString(PROFILES, array.toString()).apply()
    }

    fun activeId(context: Context): String = prefs(context).getString(ACTIVE, "primary") ?: "primary"
    fun setActive(context: Context, id: String) { prefs(context).edit().putString(ACTIVE, id).apply() }
    fun active(context: Context): NexvioProfile = all(context).firstOrNull { it.id == activeId(context) }
        ?: all(context).firstOrNull()
        ?: NexvioProfile("unsaved", "", "fox", isPrimary = false)

    fun hasSavedProfiles(context: Context): Boolean = all(context).isNotEmpty()
    fun find(context: Context, id: String): NexvioProfile? = all(context).firstOrNull { it.id == id }

    @Synchronized fun create(context: Context, name: String, avatarId: String, customUrl: String, usePrimaryAddons: Boolean): NexvioProfile {
        val profiles = all(context)
        require(name.isNotBlank()) { "Enter a profile name." }
        require(profiles.none { it.name.equals(name.trim(), true) }) { "A profile with that name already exists." }
        val profile = NexvioProfile(UUID.randomUUID().toString(), name.trim(), avatarId, customUrl.trim(),
            isPrimary = profiles.isEmpty(), usePrimaryAddons = usePrimaryAddons)
        save(context, profiles + profile)
        if (profiles.isEmpty()) setActive(context, profile.id)
        return profile
    }

    @Synchronized fun update(context: Context, updated: NexvioProfile) {
        val profiles = all(context)
        require(profiles.none { it.id != updated.id && it.name.equals(updated.name.trim(), true) }) { "A profile with that name already exists." }
        save(context, profiles.map { if (it.id == updated.id) updated.copy(name = updated.name.trim(), updatedAt = System.currentTimeMillis()) else it })
    }

    @Synchronized fun delete(context: Context, id: String) {
        val profiles = all(context)
        require(profiles.size > 1) { "Keep at least one profile." }
        require(profiles.any { it.id == id }) { "Profile not found." }

        val remaining = profiles.filterNot { it.id == id }
        // If the primary profile is deleted, promote the oldest remaining profile
        // so the account always has exactly one primary profile.
        val deletedPrimary = profiles.first { it.id == id }.isPrimary
        val normalized = if (deletedPrimary) {
            val nextPrimary = remaining.first()
            remaining.map { profile -> profile.copy(isPrimary = profile.id == nextPrimary.id) }
        } else {
            remaining
        }
        // Only signed-in account deletions need a cloud tombstone. Scope it by account
        // so a local-only profile deletion cannot affect a different account later.
        if (hasNexvioAccountSession(context)) {
            val tombstonePrefs = context.applicationContext.getSharedPreferences("nexvio_profile_tombstones", Context.MODE_PRIVATE)
            val key = nexvioProfileTombstoneKey(context)
            val tombstones = tombstonePrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            tombstones.add(id)
            tombstonePrefs.edit().putStringSet(key, tombstones).apply()
        }

        save(context, normalized)
        if (deletedPrimary) {
            // The primary profile uses the shared settings bucket. Move the promoted
            // profile's own settings into that bucket instead of inheriting the deleted
            // primary profile's preferences.
            val promoted = normalized.first { it.isPrimary }
            val source = context.getSharedPreferences("nexvio_settings_profile_${promoted.id}", Context.MODE_PRIVATE)
            val target = context.getSharedPreferences("nexvio_settings", Context.MODE_PRIVATE)
            val editor = target.edit().clear()
            source.all.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
            source.edit().clear().commit()
        }
        if (activeId(context) == id) setActive(context, normalized.first().id)
    }
}

@Composable
internal fun ProfileSystemScreen(
    onProfileSelected: (NexvioProfile) -> Unit,
    onExit: () -> Unit,
    requireSelection: Boolean
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(NexvioProfileStore.all(context)) }
    var activeId by remember { mutableStateOf(NexvioProfileStore.activeId(context)) }
    var managing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<NexvioProfile?>(null) }
    var creating by remember { mutableStateOf(profiles.isEmpty() && requireSelection) }
    var deleteTarget by remember { mutableStateOf<NexvioProfile?>(null) }
    var deletionStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() { profiles = NexvioProfileStore.all(context); activeId = NexvioProfileStore.activeId(context) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101923), ProfileBg, Color.Black)))) {
        if (creating || editor != null || profiles.isEmpty()) {
            ProfileEditorScreen(
                existing = editor,
                profiles = profiles,
                onBack = {
                    if (profiles.isEmpty() && requireSelection) {
                        // First profile is required; don't let a new user skip setup.
                    } else { creating = false; editor = null }
                },
                onSave = { name, avatar, url, addons ->
                    if (editor == null) NexvioProfileStore.create(context, name, avatar, url, addons)
                    else NexvioProfileStore.update(context, editor!!.copy(name = name, avatarId = avatar, customAvatarUrl = url, usePrimaryAddons = addons))
                    refresh(); creating = false; editor = null
                    if (requireSelection && profiles.isEmpty()) {
                        val created = NexvioProfileStore.active(context)
                        onProfileSelected(created)
                    }
                }
            )
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(32.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(ProfileGreen), contentAlignment = Alignment.Center) {
                                Text("N", color = ProfileBg, fontWeight = FontWeight.Black, fontSize = 25.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("NEXVIO", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        }
                        Spacer(Modifier.height(30.dp))
                        Text(if (managing) "Manage Profiles" else if (profiles.isEmpty()) "Create your profile" else "Who's watching?", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
                        Text(if (managing) "Edit your profiles" else if (profiles.isEmpty()) "Choose a name and avatar to get started" else "Choose a profile to continue", color = ProfileMuted, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(28.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Box {
                                ProfileAvatar(profile.avatarId, profile.customAvatarUrl, 88.dp,
                                    selected = profile.id == activeId && !managing,
                                    modifier = Modifier.clickable {
                                        if (managing) editor = profile
                                        else {
                                            NexvioProfileStore.setActive(context, profile.id); activeId = profile.id
                                            onProfileSelected(profile)
                                        }
                                    })
                                if (managing) {
                                    Box(Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(ProfileGreen), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.name}", tint = ProfileBg, modifier = Modifier.size(17.dp))
                                    }
                                    if (profiles.size > 1) {
                                        Box(Modifier.align(Alignment.TopEnd).size(28.dp).clip(CircleShape).background(Color(0xFF542B32))
                                            .clickable { deleteTarget = profile }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete ${profile.name}", tint = Color.White, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                } else if (profile.isPrimary) {
                                    Box(Modifier.align(Alignment.BottomEnd).size(25.dp).clip(CircleShape).background(ProfileGreen), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Check, contentDescription = "Primary profile", tint = ProfileBg, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(profile.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (profile.isPrimary) Text("PRIMARY", color = ProfileGreen, fontSize = 9.sp, letterSpacing = 1.2.sp)
                            if (managing && !profile.isPrimary) Text("Edit profile", color = ProfileMuted, fontSize = 11.sp,
                                modifier = Modifier.clickable { editor = profile }.padding(4.dp))
                        }
                    }
                    if (!managing) item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().clickable { creating = true }) {
                            Box(Modifier.size(88.dp).clip(CircleShape).border(1.dp, Color(0xFF45515B), CircleShape).background(Color(0x33252D35)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "Add profile", tint = Color.White, modifier = Modifier.size(38.dp))
                            }
                            Spacer(Modifier.height(9.dp))
                            Text("Add Profile", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                deletionStatus?.let { Text(it, color = ProfileMuted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (managing) {
                        OutlinedButton(onClick = { managing = false; refresh() }, shape = RoundedCornerShape(50), colors = ButtonDefaults.outlinedButtonColors(contentColor = ProfileGreen)) { Text("Done") }
                    } else {
                        OutlinedButton(onClick = { managing = true }, shape = RoundedCornerShape(50), colors = ButtonDefaults.outlinedButtonColors(contentColor = ProfileMuted)) { Text("Manage Profiles") }
                        if (!requireSelection) {
                            Spacer(Modifier.width(10.dp))
                            TextButton(onClick = onExit) { Text("Back to Nexvio", color = ProfileGreen) }
                        }
                    }
                }
            }
        }
    }
    if (deleteTarget != null) AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("Delete ${deleteTarget!!.name}?") },
        text = { Text(
            (if (deleteTarget!!.isPrimary) "Another remaining profile will automatically become primary. " else "") +
                (if (hasNexvioAccountSession(context)) "This profile will be permanently deleted from your Nexvio account and removed across devices after sync. " else "This profile will be permanently deleted from this device. ") +
                "This action cannot be undone."
        ) },
        confirmButton = { TextButton(onClick = {
            val targetId = deleteTarget!!.id
            val result = runCatching { NexvioProfileStore.delete(context, targetId) }
            deleteTarget = null
            refresh()
            if (result.isFailure) {
                deletionStatus = result.exceptionOrNull()?.message ?: "Could not delete this profile."
            } else if (hasNexvioAccountSession(context)) {
                deletionStatus = "Deleting profile across your devices…"
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { syncNexvioAccount(context) }
                        deletionStatus = "Profile deletion synced across your Nexvio account."
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        deletionStatus = "Deleted locally. Cloud sync will retry when a connection is available."
                    }
                }
            } else {
                deletionStatus = "Profile deleted from this device."
            }
        }) { Text("Delete", color = Color(0xFFFF7777)) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
    )
}

@Composable
private fun ProfileEditorScreen(
    existing: NexvioProfile?,
    profiles: List<NexvioProfile>,
    onBack: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "New profile") }
    var avatarId by remember(existing?.id) { mutableStateOf(existing?.avatarId ?: "fox") }
    var customUrl by remember(existing?.id) { mutableStateOf(existing?.customAvatarUrl ?: "") }
    val usePrimaryAddons = true
    var error by remember { mutableStateOf<String?>(null) }
    var imageError by remember { mutableStateOf(false) }
    val trimmedName = name.trim()
    val duplicate = profiles.any { it.id != existing?.id && it.name.equals(trimmedName, true) }
    val urlValid = customUrl.isBlank() || runCatching {
        val uri = URI(customUrl.trim())
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
    val valid = trimmedName.isNotBlank() && trimmedName.length <= 32 && !duplicate && urlValid

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text(if (existing == null) "Add Profile" else "Edit Profile", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(ProfileCard).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(avatarId, customUrl, 82.dp, modifier = Modifier)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(trimmedName.ifBlank { "New profile" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (existing?.isPrimary == true) "Primary profile" else "Standard profile", color = ProfileMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(if (customUrl.isNotBlank() && urlValid) "Custom avatar" else NexvioAvatars.firstOrNull { it.id == avatarId }?.name ?: "Avatar", color = ProfileGreen, fontSize = 12.sp)
                    Text("Add-ons and plugins shared across profiles", color = ProfileMuted, fontSize = 11.sp)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ProfileCard).padding(16.dp)) {
                Text("Profile name", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(value = name, onValueChange = { name = it; error = null }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, placeholder = { Text("Enter a name") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ProfileField, unfocusedContainerColor = ProfileField,
                        focusedBorderColor = ProfileGreen, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White, cursorColor = ProfileGreen))
                if (trimmedName.isBlank()) Text("Enter a profile name.", color = Color(0xFFFF8585), fontSize = 12.sp)
                else if (trimmedName.length > 32) Text("Use 32 characters or fewer.", color = Color(0xFFFF8585), fontSize = 12.sp)
                else if (duplicate) Text("That profile name is already in use.", color = Color(0xFFFF8585), fontSize = 12.sp)
                Text("Add-ons and plugins are shared by every profile. Your library, downloads, watch history, and viewing progress remain separate.", color = ProfileMuted, fontSize = 13.sp)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ProfileCard).padding(16.dp)) {
                Text("Custom avatar URL", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Paste an image link, or leave this empty to use the built-in avatar catalog.", color = ProfileMuted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = customUrl, onValueChange = { customUrl = it; imageError = false; error = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("https://example.com/avatar.png") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ProfileField, unfocusedContainerColor = ProfileField,
                        focusedBorderColor = ProfileGreen, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White, cursorColor = ProfileGreen))
                if (!urlValid) Text("Enter a valid HTTP or HTTPS image URL.", color = Color(0xFFFF8585), fontSize = 12.sp)
                else if (imageError) Text("The image could not be loaded. Check the link or clear it to use the built-in avatar.", color = Color(0xFFFF8585), fontSize = 12.sp)
                if (customUrl.isNotBlank() && urlValid) {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(model = customUrl.trim(), contentDescription = "Custom avatar preview",
                        modifier = Modifier.size(72.dp).clip(CircleShape).border(2.dp, ProfileGreen, CircleShape),
                        onError = { imageError = true })
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ProfileCard).padding(16.dp)) {
                Text("Choose an avatar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(NexvioAvatars.firstOrNull { it.id == avatarId }?.name ?: "Avatar", color = ProfileGreen, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().heightIn(max = 310.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = true) {
                    items(NexvioAvatars, key = { it.id }) { avatar ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { avatarId = avatar.id; customUrl = ""; imageError = false }) {
                            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(avatar.color))
                                .border(if (avatar.id == avatarId && customUrl.isBlank()) 3.dp else 1.dp,
                                    if (avatar.id == avatarId && customUrl.isBlank()) ProfileGreen else Color(0xFF46515B), CircleShape),
                                contentAlignment = Alignment.Center) { Text(avatar.emoji, fontSize = 29.sp) }
                            Spacer(Modifier.height(4.dp))
                            Text(avatar.name, color = if (avatar.id == avatarId) ProfileGreen else ProfileMuted, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (error != null) Text(error!!, color = Color(0xFFFF8585), fontSize = 12.sp)
        }
        Button(onClick = {
            if (!valid) { error = when { trimmedName.isBlank() -> "Enter a profile name."; duplicate -> "That profile name is already in use."; !urlValid -> "Enter a valid image URL."; else -> "Check the profile details." } }
            else if (customUrl.isNotBlank() && imageError) error = "The custom image failed to load. Clear the URL or use a working image link."
            else runCatching { onSave(trimmedName, avatarId, customUrl.trim(), usePrimaryAddons) }
                .onFailure { error = it.message ?: "Could not save the profile." }
        }, enabled = valid, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).height(54.dp),
            shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = ProfileGreen, contentColor = ProfileBg,
                disabledContainerColor = Color(0xFF344438), disabledContentColor = Color(0xFF849487))) {
            Text(if (existing == null) "Create Profile" else "Save Changes", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ProfileAvatar(
    avatarId: String,
    customUrl: String,
    size: androidx.compose.ui.unit.Dp,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val avatar = NexvioAvatars.firstOrNull { it.id == avatarId } ?: NexvioAvatars.first()
    var imageFailed by remember(customUrl) { mutableStateOf(false) }
    Box(modifier.size(size).clip(CircleShape).background(Color(avatar.color))
        .border(if (selected) 3.dp else 2.dp, if (selected) ProfileGreen else Color(0xFF35414B), CircleShape),
        contentAlignment = Alignment.Center) {
        if (customUrl.isNotBlank() && !imageFailed) {
            AsyncImage(model = customUrl, contentDescription = "Profile avatar",
                modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                onError = { imageFailed = true })
        } else Text(avatar.emoji, fontSize = (size.value * 0.43f).sp)
        if (selected) Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(ProfileGreen), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = ProfileBg, modifier = Modifier.size(14.dp))
        }
    }
}
