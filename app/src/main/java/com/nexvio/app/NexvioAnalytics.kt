package com.nexvio.app

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/**
 * Sends one anonymous app_open event for each process launch.
 * The installation UUID stays stable across launches; no account or personal data is sent.
 */
internal object NexvioAnalytics {
    private const val TAG = "NexvioAnalytics"
    private const val PREFS = "nexvio_analytics"
    private const val INSTALLATION_ID_KEY = "installation_id"

    fun trackAppOpen(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installationId = prefs.getString(INSTALLATION_ID_KEY, null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(INSTALLATION_ID_KEY, it).commit()
            }

        val version = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull()
            ?.let { Regex("\\d+(?:\\.\\d+){0,3}").find(it)?.value }
            ?.takeIf { it.isNotBlank() }
            ?: "1.0"

        Thread({
            var connection: HttpURLConnection? = null
            try {
                val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
                val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
                connection = (URL("$baseUrl/rest/v1/nexvio_analytics_events").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("apikey", publishableKey)
                    setRequestProperty("Authorization", "Bearer $publishableKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "return=minimal")
                }
                val payload = """[{"installation_id":"$installationId","event_type":"app_open","app_version":"$version","occurred_at":"${Instant.now()}"}]"""
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) Log.w(TAG, "App-open event was not accepted (HTTP $status).")
            } catch (error: Exception) {
                Log.w(TAG, "Unable to send app-open event.", error)
            } finally {
                connection?.disconnect()
            }
        }, "Nexvio-Analytics").apply { isDaemon = true }.start()
    }
}
