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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val AccountAccent = Color(0xFFB7F36B)
private val AccountPanel = Color(0xFF171D23)
private val AccountMuted = Color(0xFF9AA4AE)

/**
 * Supabase Auth uses only the project URL and publishable client key in the Android app.
 * User sessions are kept in private app preferences; never put a service-role key here.
 */
private object NexvioSupabaseAuth {
    private const val PREFS = "nexvio_supabase_session"
    private const val ACCESS = "access_token"
    private const val REFRESH = "refresh_token"
    private const val EMAIL = "email"
    private const val BASE_URL = BuildConfig.SUPABASE_URL
    private const val API_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    data class Session(val accessToken: String, val refreshToken: String, val email: String)

    fun session(context: Context): Session? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val access = p.getString(ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Session(access, p.getString(REFRESH, "").orEmpty(), p.getString(EMAIL, "").orEmpty())
    }

    private fun save(context: Context, json: JSONObject, fallbackEmail: String) {
        val user = json.optJSONObject("user")
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        require(access.isNotBlank()) { "Check your email to confirm your account, then sign in." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACCESS, access)
            .putString(REFRESH, refresh)
            .putString(EMAIL, user?.optString("email")?.takeIf { !it.isNullOrBlank() } ?: fallbackEmail)
            .apply()
    }

    fun signUp(context: Context, email: String, password: String): String {
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

    fun sendPasswordReset(email: String) {
        request("auth/v1/recover", "POST", JSONObject().put("email", email))
    }

    fun signOut(context: Context) {
        val s = session(context)
        if (s != null) runCatching { request("auth/v1/logout", "POST", JSONObject(), s.accessToken) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
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

@Composable
internal fun AccountSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(NexvioSupabaseAuth.session(context)) }
    var email by remember { mutableStateOf(session?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun perform(action: suspend () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { action() }
                session = NexvioSupabaseAuth.session(context)
                if (session != null) email = session?.email.orEmpty()
                isError = false
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
                Text("Sync your viewing profiles and profile-specific app settings to your Nexvio account. Downloads stay on this device, and add-ons remain shared.", color = AccountMuted, fontSize = 12.sp)
                Button(
                    onClick = {
                        val activeSession = session
                        if (activeSession == null) {
                            message = "Sign in again to sync your account."
                            isError = true
                        } else {
                            perform {
                                val result = NexvioCloudSync.sync(context, activeSession.accessToken)
                                result.message
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AccountAccent, contentColor = Color(0xFF101510)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Syncing…" else "Sync profiles and settings") }
                Button(
                    onClick = { perform { NexvioSupabaseAuth.signOut(context); "Signed out of Nexvio." } },
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
                    onClick = { perform { NexvioSupabaseAuth.signIn(context, email.trim(), password) } },
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
