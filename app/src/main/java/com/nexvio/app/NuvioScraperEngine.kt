package com.nexvio.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NuvioDiscoveredStream(val providerName: String, val title: String, val url: String, val quality: String, val headers: Map<String, String> = emptyMap())
internal object NuvioDiagnosticFormat {
    fun safeUrl(address: String): String = try {
        val uri = URI(address.trim())
        require((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank())
        val host = uri.host.orEmpty()
        val port = if (uri.port >= 0) ":" + uri.port else ""
        val path = uri.rawPath.orEmpty()
        val safePath = if (path.matches(Regex("(?i).*\\.(m3u8|mpd|mp4|mkv|webm|ts|m4v|mp3|aac)(\\..*)?$")) || path.length > 180) "/[stream-or-long-path]" else path.take(240)
        uri.scheme + "://" + host + port + safePath
    } catch (_: Exception) { if (address.contains("://")) "(unparseable-url)" else address.substringBefore('?').take(120) }

    fun timeoutMessage(count: Int, lastUrl: String, pending: Boolean): String =
        String.format(java.util.Locale.ROOT, "Provider timed out (%d requests, last: %s; request pending: %s)", count, lastUrl, pending)
}

internal data class NuvioRequestLog(val method: String, val url: String, val status: Int, val elapsedMs: Long, val responseBytes: Int, val error: String? = null) {
    fun format(): String = buildString {
        append(method).append(' ').append(url).append(" → ").append(status)
        append(" • ").append(elapsedMs).append(" ms • ").append(responseBytes).append(" bytes")
        if (!error.isNullOrBlank()) append(" • ").append(error)
    }
}

/**
 * Runs compatible Nuvio CommonJS providers in a disposable JavaScript WebView.
 * This is intentionally a restricted adapter, not a full Node/Hermes clone:
 * Node built-ins and arbitrary require() modules are unavailable.
 */
internal object NuvioScraperEngine {
    const val ENGINE_VERSION = "engine-3"
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val LOGS_PER_PROVIDER = 30
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(16)
    private val requestLogs = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<NuvioRequestLog>>()
    private val unsupportedModules = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    fun requestLogs(provider: String): List<NuvioRequestLog> = synchronized(requestLogs) { requestLogs[provider]?.toList().orEmpty() }
    fun unsupportedModules(provider: String): Set<String> = unsupportedModules[provider]?.toSet().orEmpty()
    private fun recordRequest(provider: String, entry: NuvioRequestLog) { synchronized(requestLogs) { val logs = requestLogs.getOrPut(provider) { java.util.ArrayDeque() }; logs.addLast(entry); while (logs.size > LOGS_PER_PROVIDER) logs.removeFirst() } }
    private fun recordUnsupportedModule(provider: String, module: String) { unsupportedModules.computeIfAbsent(provider) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }.add(module) }
    private fun safeLogUrl(address: String): String = NuvioDiagnosticFormat.safeUrl(address)
    private fun safeLogError(message: String?): String? = message?.replace(Regex("https?://[^\\s\\\"'<>]+"), { match -> safeLogUrl(match.value) })?.take(300)
    private class RequestTracker {
        @Volatile var count: Int = 0; private set
        @Volatile var pending: Int = 0; private set
        @Volatile var lastUrl: String = "(none)"; private set
        @Synchronized fun started(url: String) { count++; pending++; lastUrl = url }
        @Synchronized fun finished() { pending = (pending - 1).coerceAtLeast(0) }
        fun timeoutMessage(): String = NuvioDiagnosticFormat.timeoutMessage(count, lastUrl, pending > 0)
    }

    suspend fun discover(context: Context, item: TmdbTitle, season: Int, episode: Int, onStreams: suspend (List<NuvioDiscoveredStream>) -> Unit = {}, onStatus: suspend (String) -> Unit = {}, providerFilter: String? = null, disabledProviders: Set<String> = emptySet()): List<NuvioDiscoveredStream> {
        // Run a small number of providers concurrently so one slow scraper does not
        // block faster providers from publishing their results.
        val providers = PluginRepositoryStore(context).all().filter { it.enabled }
            .flatMap { repository ->
                repository.providers.filter {
                    it.enabled &&
                        (providerFilter == null || it.name.equals(providerFilter, true)) &&
                        it.name !in disabledProviders
                }.map { provider -> Triple(repository.name, provider.name, provider.fileUrl) }
            }
        val results = java.util.Collections.synchronizedList(mutableListOf<NuvioDiscoveredStream>())
        val publishLock = kotlinx.coroutines.sync.Mutex()
        val concurrency = kotlinx.coroutines.sync.Semaphore(6)

        return kotlinx.coroutines.coroutineScope {
            val jobs = providers.map { (repositoryName, providerName, fileUrl) ->
                async {
                    concurrency.withPermit {
                        onStatus(providerName + ": waiting for search slot")
                        onStatus(providerName + ": downloading script")
                        val script = try { fetchText(fileUrl, MAX_BYTES) } catch (e: Exception) { onStatus(providerName + ": network/download error — " + (e.message ?: "network error")); return@withPermit }
                        var failed = false
                        val found = try {
                            onStatus(providerName + ": script downloaded; searching")
                            execute(context, providerName.ifBlank { repositoryName }, script, item, season, episode, onStatus)
                        } catch (e: Exception) {
                            failed = true
                            onStatus(providerName + ": failed — " + (e.message ?: e.javaClass.simpleName))
                            emptyList()
                        }
                        if (!failed) onStatus(if (found.isEmpty()) providerName + ": completed — no streams" else providerName + ": completed — " + found.size + " stream(s)")
                        if (found.isNotEmpty()) {
                            publishLock.withLock {
                                results.addAll(found)
                                val snapshot = results.distinctBy { it.url }
                                onStreams(snapshot)
                            }
                        }
                    }
                }
            }
            jobs.forEach { job -> try { job.await() } catch (_: Exception) { } }
            results.distinctBy { it.url }.take(50)
        }
    }

    private suspend fun execute(context: Context, name: String, source: String, item: TmdbTitle, season: Int, episode: Int, onStatus: suspend (String) -> Unit): List<NuvioDiscoveredStream> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context)
                val tracker = RequestTracker()
                val bridge = Bridge(web, name, tracker)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = false
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val src = JSONObject.quote(source)
                        val id = JSONObject.quote(item.id.toString())
                        val type = JSONObject.quote(if (item.mediaType == "movie") "movie" else "tv")
                        val s = if (item.mediaType == "movie") "null" else season.toString()
                        val e = if (item.mediaType == "movie") "null" else episode.toString()
                        val cryptoJsSource = context.assets.open("crypto-js.min.js").bufferedReader().use { it.readText() }
                        val js = context.assets.open("nexvio-prelude.js").bufferedReader().use { it.readText() }
                            .replace("__NEXVIO_CRYPTO_JS__", cryptoJsSource)
                            .replace("__NEXVIO_SOURCE_JSON__", src)
                            .replace("__NEXVIO_ID_JSON__", id)
                            .replace("__NEXVIO_TYPE_JSON__", type)
                            .replace("__NEXVIO_SEASON__", s)
                            .replace("__NEXVIO_EPISODE__", e)
                        view.evaluateJavascript(js) { result ->
                    android.util.Log.d("NexvioNuvio", "$name evaluateJavascript result: " + (result ?: "null"))
                }
                main.postDelayed({
                    if (continuation.isActive && !bridge.scriptStarted) {
                        continuation.resumeWithException(IllegalStateException("Injected script did not start (JS syntax error?)"))
                        web.stopLoading()
                        web.removeJavascriptInterface("NexvioBridge")
                        web.destroy()
                    }
                }, 4_000)
                    }
                }
                bridge.done = done@{ raw ->
                    if (continuation.isActive) {
                        val streams = try {
                            val root = JSONObject(raw)
                            if (!root.optBoolean("ok")) {
                                val message = root.optString("error", "unknown provider error")
                                android.util.Log.w("NexvioNuvio", "${name} failed: $message")
                                throw IllegalStateException(message)
                            }
                            val parsed = root.optJSONArray("streams") ?: JSONArray()
                            (0 until parsed.length()).mapNotNull { i ->
                                val o = parsed.optJSONObject(i) ?: return@mapNotNull null
                                val url = o.optString("url").trim()
                                if (!publicHttp(url)) return@mapNotNull null
                                val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { name }
                                val quality = o.optString("quality").ifBlank {
                                    Regex("(?i)(2160p|1080p|720p|480p|360p|4k|cam)").find(title)?.value?.uppercase() ?: "Quality unknown"
                                }
                                val headerObject = o.optJSONObject("headers")
                                val headers = headerObject?.let { obj -> buildMap { val keys = obj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, obj.optString(key)) } } }.orEmpty()
                                NuvioDiscoveredStream(name, title, url, quality, headers)
                            }
                        } catch (e: Exception) {
                            continuation.resumeWithException(
                                IllegalStateException("Provider response/runtime error: ${e.message ?: "invalid response"}", e)
                            )
                            main.post { web.stopLoading(); web.removeJavascriptInterface("NexvioBridge"); web.destroy() }
                            return@done
                        }
                        continuation.resume(streams)
                    }
                    main.post { web.stopLoading(); web.removeJavascriptInterface("NexvioBridge"); web.destroy() }
                }
                web.addJavascriptInterface(bridge, "NexvioBridge")
                continuation.invokeOnCancellation { main.post { web.stopLoading(); web.destroy() } }
                main.postDelayed({
                    if (continuation.isActive) {
                        continuation.resumeWithException(TimeoutException(tracker.timeoutMessage()))
                        web.stopLoading(); web.removeJavascriptInterface("NexvioBridge"); web.destroy()
                    }
                }, 60000)
                web.loadDataWithBaseURL("https://nexvio.invalid/", "<html><body></body></html>", "text/html", "UTF-8", null)
            }
        }

    private class Bridge(private val web: WebView, private val provider: String, private val tracker: RequestTracker) {
        @Volatile var done: ((String) -> Unit)? = null
        @Volatile var scriptStarted: Boolean = false
        @JavascriptInterface fun started() { scriptStarted = true }
        @JavascriptInterface fun finish(result: String) { done?.invoke(result.take(512 * 1024)) }
        @JavascriptInterface fun unsupportedModule(name: String) { recordUnsupportedModule(provider, name.take(120)) }
        @JavascriptInterface fun request(id: String, address: String, optionsText: String) {
            val safeUrl = safeLogUrl(address)
            tracker.started(safeUrl)
            pool.execute {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                var methodForLog = "GET"
                var statusForLog = 599
                var bytesForLog = 0
                var errorForLog: String? = null
                val payload = try {
                    val uri = URI(address.trim())
                    require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null)
                    val host = uri.host.lowercase()
                    val ip = java.net.InetAddress.getByName(host)
                    require(host != "localhost" && !host.endsWith(".local") && !ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isLinkLocalAddress && !ip.isSiteLocalAddress) { "Requests to local/private hosts are blocked." }
                    val options = JSONObject(optionsText)
                    val method = options.optString("method", "GET").uppercase()
                    methodForLog = method
                    require(method == "GET" || method == "POST" || method == "HEAD")
                    val connection = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 15_000
                        connection.instanceFollowRedirects = options.optString("redirect", "follow") != "manual"
                        connection.requestMethod = method
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.88 Mobile Safari/537.36")
                        val headers = options.optJSONObject("headers")
                        if (headers != null) { val keys = headers.keys(); while (keys.hasNext()) { val key = keys.next(); if (!key.equals("host", true) && !key.equals("content-length", true)) connection.setRequestProperty(key.take(80), headers.optString(key).take(2000)) } }
                        val body = options.optString("body")
                        if (method == "POST" && body.isNotBlank()) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8).take(256 * 1024).toByteArray()) } }
                        val status = connection.responseCode
                        statusForLog = status
                        val stream = if (status >= 400) connection.errorStream else connection.inputStream
                        val bytes = stream?.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096); var total = 0; while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response exceeds 2 MB"); out.write(buffer, 0, n) }; out.toByteArray() } ?: ByteArray(0)
                        bytesForLog = bytes.size
                        JSONObject().put("status", status).put("body", bytes.toString(Charsets.UTF_8)).put("statusText", connection.responseMessage.orEmpty()).put("url", connection.url.toString()).put("headers", JSONObject().apply { connection.headerFields.forEach { (key, values) -> if (!key.isNullOrBlank() && !values.isNullOrEmpty()) put(key.lowercase(), values.joinToString(", ")) } }).toString()
                    } finally { connection.disconnect() }
                } catch (e: Exception) {
                    errorForLog = safeLogError(e.message) ?: e.javaClass.simpleName
                    JSONObject().put("status", 599).put("body", errorForLog ?: "Network request failed").put("error", errorForLog).toString()
                } finally {
                    recordRequest(provider, NuvioRequestLog(methodForLog, safeUrl, statusForLog, android.os.SystemClock.elapsedRealtime() - startedAt, bytesForLog, errorForLog))
                    tracker.finished()
                }
                val jsId = JSONObject.quote(id)
                val jsPayload = JSONObject.quote(payload)
                main.post { web.evaluateJavascript("window.__nexvioFetchDone(" + jsId + "," + jsPayload + ")", null) }
            }
        }
    }

    private fun publicHttp(value: String): Boolean = try {
        val uri = URI(value)
        if (!(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) || uri.host.isNullOrBlank() || uri.userInfo != null) false
        else {
            val host = uri.host.lowercase()
            val address = java.net.InetAddress.getByName(host)
            host != "localhost" && !host.endsWith(".local") && !address.isAnyLocalAddress &&
                !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress
        }
    } catch (_: Exception) { false }

    private suspend fun fetchText(address: String, limit: Int): String = withContext(Dispatchers.IO) {
        val uri = URI(address)
        require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null)
        val c = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.88 Mobile Safari/537.36")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            if (c.contentLengthLong > limit) throw IllegalStateException("Provider file exceeds size limit")
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw IllegalStateException("Provider file exceeds size limit")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
}
