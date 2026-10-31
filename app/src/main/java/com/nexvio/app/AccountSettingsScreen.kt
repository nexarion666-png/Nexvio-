package com.nexvio.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val AccountAccent = Color(0xFFB7F36B)
private val AccountPanel = Color(0xFF171D23)
private val AccountMuted = Color(0xFF9AA4AE)
private val nexvioAccountSyncMutex = Mutex()

/**
 * Supabase Auth uses only the project URL and publishable client key in the Android app.
 * User sessions are kept in private app preferences; never put a service-role key here.
 */
private object NexvioSupabaseAuth {
    private const val PREFS = "nexvio_supabase_session"
    private const val ACCESS = "access_token"
    private const val REFRESH = "refresh_token"
    private const val EMAIL = "email"
    private const val EXPIRES_AT = "expires_at_millis"
    private const val BASE_URL = BuildConfig.SUPABASE_URL
    private const val API_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    data class Session(val accessToken: String, val refreshToken: String, val email: String, val expiresAtMillis: Long)
    private val refreshMutex = Mutex()
    private const val REFRESH_SKEW_MILLIS = 2 * 60 * 1000L

    fun session(context: Context): Session? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val access = p.getString(ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Session(
            access,
            p.getString(REFRESH, "").orEmpty(),
            p.getString(EMAIL, "").orEmpty(),
            p.getLong(EXPIRES_AT, 0L)
        )
    }

    private fun save(context: Context, json: JSONObject, fallbackEmail: String) {
        val user = json.optJSONObject("user")
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expiresAtSeconds = json.optLong("expires_at", 0L)
        val expiresInSeconds = json.optLong("expires_in", 0L)
        val expiryMillis = when {
            expiresAtSeconds > 0L -> expiresAtSeconds * 1000L
            expiresInSeconds > 0L -> System.currentTimeMillis() + expiresInSeconds * 1000L
            else -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(EXPIRES_AT, 0L)
        }
        require(access.isNotBlank()) { "Check your email to confirm your account, then sign in." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACCESS, access)
            .putString(REFRESH, refresh)
            .putString(EMAIL, user?.optString("email")?.takeIf { !it.isNullOrBlank() } ?: fallbackEmail)
            .putLong(EXPIRES_AT, expiryMillis)
            .apply()
    }

    fun signUp(context: Context, email: String, password: String): String {
        // Do not let a previous user's cached session survive a new-account flow.
        clearSession(context)
        val result = request("auth/v1/signup", "POST", JSONObject().put("email", email).put("password", password))
        if (result.has("access_token")) {
            save(context, result, email)
            return "Account created and signed in."
        }
        return "Account created. Check your email for the confirmation link, then sign in."
    }

    fun signIn(context: Context, email: String, password: String): String {
        val result = request("auth/v1/token?grant_type=password", "POST", JSONObject().put("email", email).put("password", password))
        save(context, result, email)
        return "You're signed in. Your Nexvio account is available on this device."
    }

    /**
     * Reuse a valid access token. Refresh only near expiry and serialize refresh-token
     * rotation so concurrent foreground/manual syncs cannot reuse the same refresh token.
     */
    suspend fun refreshSession(context: Context, forceRefresh: Boolean = false): Session? = refreshMutex.withLock {
        val current = session(context) ?: return@withLock null
        if (!forceRefresh && current.expiresAtMillis > System.currentTimeMillis() + REFRESH_SKEW_MILLIS) {
            return@withLock current
        }
        if (current.refreshToken.isBlank()) {
            clearSession(context)
            throw IllegalStateException("Sign in again to sync your account.")
        }
        try {
            val result = request(
                "auth/v1/token?grant_type=refresh_token",
                "POST",
                JSONObject().put("refresh_token", current.refreshToken)
            )
            save(context, result, current.email)
            session(context) ?: throw IllegalStateException("Sign in again to sync your account.")
        } catch (e: Exception) {
            val reason = e.message.orEmpty()
            if (isInvalidSupabaseSessionError(reason)) {
                clearSession(context)
                throw IllegalStateException("Your cloud session is no longer valid. Sign in again to resume sync.")
            }
            throw e
        }
    }

    private fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun sendPasswordReset(email: String) {
        request("auth/v1/recover", "POST", JSONObject().put("email", email))
    }

    fun sendSignInLink(email: String) {
        request("auth/v1/otp", "POST", JSONObject().put("email", email).put("create_user", false))
    }

    fun clearSessionForReauthentication(context: Context) {
        clearSession(context)
    }

    fun signOut(context: Context) {
        val s = session(context)
        if (s != null) runCatching { request("auth/v1/logout", "POST", JSONObject(), s.accessToken) }
        clearSession(context)
    }

    private fun request(path: String, method: String, body: JSONObject, bearer: String? = null): JSONObject {
        require(BASE_URL.isNotBlank() && API_KEY.isNotBlank()) { "Supabase is not configured. Check the app's Supabase build configuration." }
        val connection = (URL("${BASE_URL.trimEnd('/')}/${path.trimStart('/')}").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("apikey", API_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val message = json.optString("msg").ifBlank { json.optString("message") }
                    .ifBlank { json.optString("error_description") }
                    .ifBlank { json.optString("error") }
                    .ifBlank { "Supabase request failed (HTTP $code)." }
                throw IllegalStateException(message)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}


/** Serialize manual and automatic syncs, refresh the session, and persist the result. */
private fun isMissingSupabaseSessionError(message: String?): Boolean {
    val text = message.orEmpty().lowercase()
    return "session from session_id claim" in text ||
        "session_not_found" in text ||
        "session does not exist" in text
}

private fun isInvalidSupabaseSessionError(message: String?): Boolean {
    val text = message.orEmpty().lowercase()
    return isMissingSupabaseSessionError(text) ||
        "invalid refresh token" in text ||
        "refresh token not found" in text ||
        "refresh token has expired" in text ||
        "refresh token expired" in text ||
        "refresh token already used" in text ||
        "refresh token has already been used" in text
}

internal suspend fun syncNexvioAccount(context: Context): String = nexvioAccountSyncMutex.withLock {
    val appContext = context.applicationContext
    val status = appContext.getSharedPreferences("nexvio_cloud_sync_status", Context.MODE_PRIVATE)
    if (NexvioSupabaseAuth.session(appContext) == null) {
        throw IllegalStateException("Sign in to sync your Nexvio account.")
    }
    val session = NexvioSupabaseAuth.refreshSession(appContext)
        ?: throw IllegalStateException("Sign in to sync your Nexvio account.")

    // A session can be deleted server-side while its locally stored JWT is still within
    // its expiry window. In that case, force one refresh and retry only this known error.
    val result = try {
        NexvioCloudSync.sync(appContext, session.accessToken)
    } catch (firstError: Exception) {
        if (!isMissingSupabaseSessionError(firstError.message)) throw firstError

        val recovered = try {
            NexvioSupabaseAuth.refreshSession(appContext, forceRefresh = true)
        } catch (refreshError: Exception) {
            if (isInvalidSupabaseSessionError(refreshError.message)) {
                NexvioSupabaseAuth.clearSessionForReauthentication(appContext)
                throw IllegalStateException("Your cloud session has expired. Sign in again to resume sync.")
            }
            throw refreshError
        } ?: run {
            NexvioSupabaseAuth.clearSessionForReauthentication(appContext)
            throw IllegalStateException("Your cloud session has expired. Sign in again to resume sync.")
        }

        try {
            NexvioCloudSync.sync(appContext, recovered.accessToken)
        } catch (retryError: Exception) {
            if (isMissingSupabaseSessionError(retryError.message)) {
                NexvioSupabaseAuth.clearSessionForReauthentication(appContext)
                throw IllegalStateException("Your cloud session has expired. Sign in again to resume sync.")
            }
            throw retryError
        }
    }
    status.edit().putString("message", result.message).putBoolean("error", false).apply()
    result.message
}

/** Cancellation is lifecycle control, not a cloud-sync failure. */
internal suspend fun syncNexvioAccountAutomatically(context: Context): Boolean {
    val appContext = context.applicationContext
    val status = appContext.getSharedPreferences("nexvio_cloud_sync_status", Context.MODE_PRIVATE)
    if (NexvioSupabaseAuth.session(appContext) == null) return true
    return try {
        syncNexvioAccount(appContext)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        status.edit()
            .putString("message", "Cloud sync failed: ${e.message ?: "Unknown error"}")
            .putBoolean("error", true)
            .apply()
        false
    }
}

@Composable
internal fun AccountSettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(NexvioSupabaseAuth.session(context)) }
    var email by remember { mutableStateOf(session?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val syncStatus = remember(context) { context.getSharedPreferences("nexvio_cloud_sync_status", Context.MODE_PRIVATE) }
    var message by remember { mutableStateOf(syncStatus.getString("message", null)) }
    var isError by remember { mutableStateOf(syncStatus.getBoolean("error", false)) }

    fun perform(onSuccess: (() -> Unit)? = null, action: suspend () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { action() }
                session = NexvioSupabaseAuth.session(context)
                if (session != null) email = session?.email.orEmpty()
                isError = false
                onSuccess?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = e.message ?: "The request could not be completed. Try again."
                isError = true
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF090D12)).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹  Back to Settings", color = AccountAccent) }
        Text("Nexvio Account", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Sign in with a global account to use the same identity across your devices.", color = AccountMuted, fontSize = 13.sp)
        if (session != null) {
            Column(Modifier.fillMaxWidth().background(AccountPanel, RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("You're signed in", color = AccountAccent, fontWeight = FontWeight.Bold)
                Text(session?.email.orEmpty(), color = Color.White)
                Text("Sync your profiles, settings, add-ons, plugin repositories, IPTV URL and channel favorites, watchlists, and watch history across devices. Downloaded media and device-only file imports stay local.", color = AccountMuted, fontSize = 12.sp)
                Button(
                    onClick = {
                        val activeSession = session
                        if (activeSession == null) {
                            message = "Sign in again to sync your account."
                            isError = true
                        } else {
                            perform {
                                syncNexvioAccount(context)
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AccountAccent, contentColor = Color(0xFF101510)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Syncing…" else "Sync all Nexvio data") }
                Button(
                    onClick = { perform(onSuccess = onSignedOut) { NexvioSupabaseAuth.signOut(context); "Signed out of Nexvio." } },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AccountAccent, contentColor = Color(0xFF101510)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Please wait…" else "Sign out") }
            }
        } else {
            Column(Modifier.fillMaxWidth().background(AccountPanel, RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Create account or sign in", color = Color.White, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password (at least 6 characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Button(
                    onClick = {
                        perform {
                            val signedInMessage = NexvioSupabaseAuth.signIn(context, email.trim(), password)
                            // Sync immediately after login so the user does not need to open Settings again.
                            val activeSession = NexvioSupabaseAuth.session(context)
                            if (activeSession != null) {
                                syncNexvioAccount(context)
                            }
                            signedInMessage
                        }
                    },
                    enabled = !busy && email.contains("@") && password.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccountAccent, contentColor = Color(0xFF101510)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Please wait…" else "Sign in") }
                OutlinedButton(
                    onClick = {
                        if (password.length < 6) { message = "Use a password with at least 6 characters."; isError = true }
                        else perform { NexvioSupabaseAuth.signUp(context, email.trim(), password) }
                    },
                    enabled = !busy && email.contains("@") && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create account") }
                TextButton(
                    onClick = {
                        if (!email.contains("@")) { message = "Enter your email address first."; isError = true }
                        else perform { NexvioSupabaseAuth.sendPasswordReset(email.trim()); "If an account exists for that email, Supabase will send password-reset instructions." }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Forgot password?", color = AccountAccent) }
            }
        }
        message?.let {
            Text(it, color = if (isError) Color(0xFFFF8B8B) else AccountAccent, fontSize = 13.sp)
        }
        Text("Your password is sent directly to Supabase Auth over HTTPS and is not stored by Nexvio. Never share your password or any Supabase secret/service-role key.", color = AccountMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}


internal fun hasNexvioAccountSession(context: Context): Boolean =
    NexvioSupabaseAuth.session(context) != null

/** Stable local bucket for pending deletion tombstones, isolated per signed-in email. */
internal fun nexvioProfileTombstoneKey(context: Context): String {
    val email = NexvioSupabaseAuth.session(context)?.email?.trim()?.lowercase().orEmpty()
    return "ids_" + if (email.isBlank()) "local" else email.hashCode().toString()
}


@Composable
internal fun NexvioWelcomeScreen(
    onContinueWithoutAccount: () -> Unit,
    onAuthenticated: () -> Unit,
    externalMessage: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    fun perform(action: suspend () -> String, finishOnAuth: Boolean = false) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { action() }
                error = false
                if (finishOnAuth && NexvioSupabaseAuth.session(context) != null) onAuthenticated()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong. Please try again."
                error = true
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color(0xFF211039), Color(0xFF10091A), Color.Black)
            ))
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))
        Text("▶ NEXVIO", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text("Watch your library, anywhere", color = AccountMuted, fontSize = 15.sp)
        Spacer(Modifier.height(44.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (createAccount) "Create Account" else "Welcome Back", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (createAccount) "Create an account to sync your library and progress" else "Sign in to access your library and progress", color = AccountMuted, fontSize = 14.sp)
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = RoundedCornerShape(20.dp)
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") }, singleLine = true,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show", color = AccountMuted) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(20.dp)
        )
        Button(
            onClick = {
                if (createAccount && password.length < 6) {
                    message = "Use a password with at least 6 characters."; error = true
                } else perform({
                    if (createAccount) NexvioSupabaseAuth.signUp(context, email.trim(), password)
                    else NexvioSupabaseAuth.signIn(context, email.trim(), password)
                }, finishOnAuth = true)
            },
            enabled = !busy && email.contains("@") && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF151019))
        ) { Text(if (busy) "Please wait…" else if (createAccount) "Sign Up" else "Sign In", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (createAccount) "Already have an account? " else "Don't have an account? ", color = AccountMuted)
            TextButton(onClick = { createAccount = !createAccount; message = null }) {
                Text(if (createAccount) "Sign In" else "Sign Up", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = AccountPanel)
            Text("  or  ", color = AccountMuted)
            HorizontalDivider(Modifier.weight(1f), color = AccountPanel)
        }
        OutlinedButton(
            onClick = {
                if (!email.contains("@")) { message = "Enter your email address first."; error = true }
                else perform({ NexvioSupabaseAuth.sendSignInLink(email.trim()); "Check your email for a sign-in link." })
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) { Text("Sign In with Link", color = Color.White, fontSize = 16.sp) }

        OutlinedButton(
            onClick = onContinueWithoutAccount,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) { Text("Continue Without Account", color = Color.White, fontSize = 16.sp) }
        Text("Your data will only be stored locally when you continue without an account.", color = AccountMuted, fontSize = 12.sp)
        externalMessage?.let {
            Text(it, color = Color(0xFFFF8B8B), fontSize = 13.sp)
        }
        message?.let { Text(it, color = if (error) Color(0xFFFF8B8B) else AccountAccent, fontSize = 13.sp) }
        Spacer(Modifier.height(12.dp))
    }
}
