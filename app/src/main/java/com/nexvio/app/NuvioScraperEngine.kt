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
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

internal data class NuvioRequestLog(val method: String, val url: String, val status: Int, val elapsedMs: Long, val responseBytes: Int, val error: String? = null, val bodyPreview: String? = null, val streamShape: String? = null) {
    fun format(): String = buildString {
        append(method).append(' ').append(url).append(" → ").append(status)
        append(" • ").append(elapsedMs).append(" ms • ").append(responseBytes).append(" bytes")
        if (!error.isNullOrBlank()) append(" • ").append(error)
        if (!bodyPreview.isNullOrBlank()) append("\n  repeated-response: ").append(bodyPreview)
        if (!streamShape.isNullOrBlank()) append("\n  stream: ").append(streamShape)
    }
}

/**
 * Runs compatible Nuvio CommonJS providers in a disposable JavaScript WebView.
 * This is intentionally a restricted adapter, not a full Node/Hermes clone:
 * Node built-ins and arbitrary require() modules are unavailable.
 */
internal object NuvioScraperEngine {
    const val ENGINE_VERSION = "engine-5"
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val LOGS_PER_PROVIDER = 30
    private val consoleLogs = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<String>>()
    fun consoleLogs(provider: String): List<String> = synchronized(consoleLogs) { consoleLogs[provider]?.toList().orEmpty() }
    private fun recordConsole(provider: String, line: String) { synchronized(consoleLogs) { val lines = consoleLogs.getOrPut(provider) { java.util.ArrayDeque() }; lines.addLast(line.take(200)); while (lines.size > 50) lines.removeFirst() } }
    private val runSummaries = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private val requestLogs = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<NuvioRequestLog>>()
    private val unsupportedModules = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    fun requestLogs(provider: String): List<NuvioRequestLog> = synchronized(requestLogs) { requestLogs[provider]?.toList().orEmpty() }
    fun unsupportedModules(provider: String): Set<String> = unsupportedModules[provider]?.toSet().orEmpty()
    private fun streamDedupeKey(provider: String, url: String, title: String, quality: String, headers: Map<String, String>): String = listOf(provider, url, title, quality, headers.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }).joinToString("\u0000")
    private fun recordRequest(provider: String, entry: NuvioRequestLog) { synchronized(requestLogs) { val logs = requestLogs.getOrPut(provider) { java.util.ArrayDeque() }; logs.addLast(entry); while (logs.size > LOGS_PER_PROVIDER) logs.removeFirst() } }
    private fun recordUnsupportedModule(provider: String, module: String) { unsupportedModules.computeIfAbsent(provider) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }.add(module) }
    private fun safeLogUrl(address: String): String = NuvioDiagnosticFormat.safeUrl(address)
    internal fun streamDedupeKey(s: NuvioDiscoveredStream): String = listOf(s.providerName, s.url, s.title, s.quality, s.headers.toSortedMap().entries.joinToString("&") { it.key + "=" + it.value }).joinToString("\u0000")
    private fun safeLogError(message: String?): String? = message?.replace(Regex("https?://[^\\s\\\"'<>]+"), { match -> safeLogUrl(match.value) })?.take(300)
    private class RequestTracker {
        @Volatile var count: Int = 0; private set
        @Volatile var pending: Int = 0; private set
        @Volatile var lastUrl: String = "(none)"; private set
        @Volatile var budgetExceeded: Boolean = false; private set
        @Volatile var rejected: Int = 0; private set
        @Volatile var failed: Int = 0; private set
        @Volatile var lastError: String = "none"; private set
        private val paths = linkedMapOf<String, Int>()
        private val repeated = linkedMapOf<String, Int>()
        @Synchronized fun recordOutcome(status: Int, error: String?) { if (status == 599 || status >= 400) { failed++; lastError = error ?: "HTTP $status" } }
        @Synchronized fun reject() { rejected++ }
        @Synchronized fun repeated(method: String, url: String): Int { val key = try { val u = URI(url); method.uppercase() + " " + (u.host ?: "") + (u.rawPath.orEmpty().ifBlank { "/" }) } catch (_: Exception) { method.uppercase() + " (invalid-url)" }; val next = (repeated[key] ?: 0) + 1; repeated[key] = next; return next }
        @Synchronized fun start(url: String, displayUrl: String): Boolean {
            lastUrl = displayUrl
            val key = try { val uri = URI(url); (uri.host ?: "(unknown-host)").lowercase() + (uri.rawPath.orEmpty().ifBlank { "/" }) } catch (_: Exception) { "(invalid-url)" }
            paths[key] = (paths[key] ?: 0) + 1
            if (count >= 150 || pending >= 6) { budgetExceeded = true; rejected++; return false }
            count++; pending++; return true
        }
        @Synchronized fun finished() { pending = (pending - 1).coerceAtLeast(0) }
        @Synchronized fun diagnostics(): String = paths.entries.sortedByDescending { it.value }.take(3).joinToString("; ") { "${it.key} (${it.value})" }.ifBlank { "(none)" }
        fun budgetMessage(): String = "Provider stopped: request budget exceeded (${count} requests made, ${rejected} attempts rejected, last: ${lastUrl}); top host/paths: ${diagnostics()}"
        fun failureMessage(reason: String): String = "${reason}; top host/paths: ${diagnostics()}"
        fun timeoutMessage(): String = failureMessage(NuvioDiagnosticFormat.timeoutMessage(count, lastUrl, pending > 0))
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
                        if (!failed) onStatus(if (found.isEmpty()) providerName + ": " + (runSummaries.remove(providerName) ?: "no streams (0 requests, 0 failed, last error: none)") else providerName + ": completed — " + found.size + " stream(s)")
                        if (found.isNotEmpty()) {
                            publishLock.withLock {
                                results.addAll(found)
                                val snapshot = results.distinctBy { streamDedupeKey(it.providerName, it.url, it.title, it.quality, it.headers) }
                                onStreams(snapshot)
                            }
                        }
                    }
                }
            }
            jobs.forEach { job -> try { job.await() } catch (_: Exception) { } }
            results.distinctBy { streamDedupeKey(it.providerName, it.url, it.title, it.quality, it.headers) }.take(50)
        }
    }

    private suspend fun execute(context: Context, name: String, source: String, item: TmdbTitle, season: Int, episode: Int, onStatus: suspend (String) -> Unit): List<NuvioDiscoveredStream> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context)
                val tracker = RequestTracker()
                val runActive = AtomicBoolean(true)
                val executor = Executors.newFixedThreadPool(4)
                lateinit var bridge: Bridge
                val cleaned = AtomicBoolean(false)
                fun cleanup() {
                    if (!cleaned.compareAndSet(false, true)) return
                    runActive.set(false)
                    executor.shutdownNow()
                    main.post {
                        try { web.stopLoading() } catch (_: Exception) {}
                        try { web.removeJavascriptInterface("NexvioBridge") } catch (_: Exception) {}
                        try { web.destroy() } catch (_: Exception) {}
                    }
                }
                fun failRun(message: String) {
                    if (continuation.isActive && runActive.compareAndSet(true, false)) {
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                    cleanup()
                }
                bridge = Bridge(web, name, tracker, executor, runActive) { _, _ ->
                    main.post { failRun(tracker.budgetMessage()) }
                }
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = false
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!runActive.get()) return
                        try {
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
                                .replace("__NEXVIO_PROVIDER_JSON__", JSONObject.quote(name))
                            view.evaluateJavascript(js) { result ->
                                android.util.Log.d("NexvioNuvio", "$name evaluateJavascript result: " + (result ?: "null"))
                            }
                            main.postDelayed({
                                if (continuation.isActive && runActive.get() && !bridge.scriptStarted) {
                                    failRun(tracker.failureMessage("Injected script did not start (JS syntax error?)"))
                                }
                            }, 4_000)
                        } catch (e: Exception) {
                            failRun(tracker.failureMessage("Script setup failed: ${e.message ?: e.javaClass.simpleName}"))
                        }
                    }
                }
                bridge.done = done@{ raw ->
                    if (!runActive.get() || !continuation.isActive) return@done
                    try {
                        val root = JSONObject(raw)
                        if (!root.optBoolean("ok")) {
                            val message = root.optString("error", "unknown provider error")
                            android.util.Log.w("NexvioNuvio", "$name failed: $message")
                            failRun(tracker.failureMessage("Provider response/runtime error: $message"))
                            return@done
                        }
                        val parsed = root.optJSONArray("streams") ?: JSONArray()
                        val streams = (0 until parsed.length()).mapNotNull { i ->
                            val o = parsed.optJSONObject(i) ?: return@mapNotNull null
                            val url = o.optString("url").trim()
                            if (!publicHttp(url)) return@mapNotNull null
                            val keys = o.keys().asSequence().toList().sorted()
                            val host = try { URI(url).host ?: "unknown" } catch (_: Exception) { "unknown" }
                            android.util.Log.d("NexvioNuvio", "$name stream keys=$keys host=$host")
                            recordConsole(name, "stream keys=$keys host=$host")
                            recordRequest(name, NuvioRequestLog("STREAM", host, 200, 0, 0, streamShape = "keys=${keys.joinToString(",")}; host=$host"))
                            recordRequest(name, NuvioRequestLog("STREAM", host, 200, 0, 0, streamShape = "keys=${keys.joinToString(",")}; host=$host"))
                            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { o.optString("label") }.ifBlank { name }
                            val size = o.optString("size")
                            val qualitySource = listOf("quality","resolution","label","name","title","description").map { o.optString(it) }.plus(url).joinToString(" ")
                            val quality = Regex("(?i)(2160p|4k|1080p|720p|480p|360p)").find(qualitySource)?.value?.uppercase() ?: "$host${if (size.isNotBlank()) " • $size" else ""}"
                            val headerObject = o.optJSONObject("headers")
                            val headers = headerObject?.let { obj -> buildMap { val keys = obj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, obj.optString(key)) } } }.orEmpty()
                            NuvioDiscoveredStream(name, title, url, quality, headers)
                        }
                        if (streams.isEmpty()) runSummaries[name] = if (tracker.count > 0 && tracker.failed == tracker.count) "Failed — all requests failed (${tracker.count} requests, ${tracker.failed} failed, last error: ${tracker.lastError})" else "no streams (${tracker.count} requests, ${tracker.failed} failed, last error: ${tracker.lastError})"
                        if (runActive.compareAndSet(true, false) && continuation.isActive) continuation.resume(streams)
                    } catch (e: Exception) {
                        if (continuation.isActive && runActive.compareAndSet(true, false)) {
                            continuation.resumeWithException(IllegalStateException(tracker.failureMessage("Provider response/runtime error: ${e.message ?: "invalid response"}"), e))
                        }
                    } finally { cleanup() }
                }
                web.addJavascriptInterface(bridge, "NexvioBridge")
                continuation.invokeOnCancellation { cleanup() }
                main.postDelayed({
                    if (continuation.isActive && runActive.get()) failRun(tracker.timeoutMessage())
                }, 60_000)
                web.loadDataWithBaseURL("https://nexvio.invalid/", "<html><body></body></html>", "text/html", "UTF-8", null)
            }
        }

    private class Bridge(
        private val web: WebView,
        private val provider: String,
        private val tracker: RequestTracker,
        private val executor: java.util.concurrent.ExecutorService,
        private val runActive: AtomicBoolean,
        private val onBudgetExceeded: (Int, Int) -> Unit
    ) {
        @Volatile var done: ((String) -> Unit)? = null
        @Volatile var scriptStarted: Boolean = false
        @JavascriptInterface fun started() { if (runActive.get()) scriptStarted = true }
        @JavascriptInterface fun finish(result: String) { if (runActive.get()) done?.invoke(result.take(512 * 1024)) }
        @JavascriptInterface fun unsupportedModule(name: String) { if (runActive.get()) recordUnsupportedModule(provider, name.take(120)) }
        @JavascriptInterface fun consoleLine(line: String) { if (runActive.get()) recordConsole(provider, safeLogError(line)?.take(200) ?: line.take(200)) }
        @JavascriptInterface fun request(id: String, address: String, optionsText: String) {
            if (!runActive.get()) return
            val safeUrl = safeLogUrl(address)
            if (!tracker.start(address.trim(), safeUrl)) {
                val payload = JSONObject().put("status", 599).put("body", "Request budget exceeded").put("error", "Request budget exceeded").toString()
                val jsId = JSONObject.quote(id)
                main.post {
                    if (runActive.get()) onBudgetExceeded(tracker.count, tracker.rejected)
                }
                return
            }
            try {
                executor.execute {
                    if (!runActive.get()) { tracker.finished(); return@execute }
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    var methodForLog = "GET"
                    var statusForLog = 599
                    var bytesForLog = 0
                    var errorForLog: String? = null
                    var bodyPreview: String? = null
                    val payload = try {
                        val uri = URI(address.trim())
                        require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null)
                        val host = uri.host.lowercase()
                        val dnsTask = FutureTask { java.net.InetAddress.getByName(host) }
                        Thread(dnsTask, "nexvio-dns-check").apply { isDaemon = true; start() }
                        val ip = try { dnsTask.get(5, TimeUnit.SECONDS) } catch (e: Exception) { dnsTask.cancel(true); throw IllegalStateException("DNS lookup timed out or failed", e) }
                        require(host != "localhost" && !host.endsWith(".local") && !ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isLinkLocalAddress && !ip.isSiteLocalAddress) { "Requests to local/private hosts are blocked." }
                        if (!runActive.get()) throw InterruptedException("Provider run ended")
                        val options = JSONObject(optionsText)
                        val method = options.optString("method", "GET").uppercase()
                        val redirectMode = options.optString("redirect", "follow")
                        require(redirectMode == "manual" || redirectMode == "follow" || redirectMode == "error") { "Invalid redirect mode" }
                        methodForLog = method
                        require(method == "GET" || method == "POST" || method == "HEAD")
                        var targetUri = uri
                        var redirects = 0
                        var responseJson: String? = null
                        while (responseJson == null) {
                            val targetHost = targetUri.host?.lowercase() ?: throw IllegalArgumentException("Invalid redirect target")
                            val dnsTask = FutureTask { java.net.InetAddress.getAllByName(targetHost).toList() }
                            Thread(dnsTask, "nexvio-dns-check").apply { isDaemon = true; start() }
                            val addresses = try { dnsTask.get(5, TimeUnit.SECONDS) } catch (ex: Exception) { dnsTask.cancel(true); throw IllegalStateException("DNS lookup timed out or failed", ex) }
                            require(targetUri.scheme.equals("http", true) || targetUri.scheme.equals("https", true))
                            require(targetHost != "localhost" && !targetHost.endsWith(".local") && addresses.isNotEmpty() && addresses.all { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress }) { "Requests to local/private hosts are blocked." }
                            val connection = URL(targetUri.toASCIIString()).openConnection() as HttpURLConnection
                            try {
                                connection.connectTimeout = 10_000
                                connection.readTimeout = 15_000
                                connection.instanceFollowRedirects = false
                                connection.requestMethod = method
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.88 Mobile Safari/537.36")
                                val headers = options.optJSONObject("headers")
                                if (headers != null) { val keys = headers.keys(); while (keys.hasNext()) { val key = keys.next(); if (!key.equals("host", true) && !key.equals("content-length", true)) connection.setRequestProperty(key.take(80), headers.optString(key).take(2000)) } }
                                val body = options.optString("body")
                                if (method == "POST" && body.isNotBlank() && redirects == 0) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8).take(256 * 1024).toByteArray()) } }
                                if (!runActive.get()) throw InterruptedException("Provider run ended")
                                val status = connection.responseCode
                                statusForLog = status
                                val location = connection.getHeaderField("Location")
                                if (status in 300..399 && redirectMode == "error") throw IllegalStateException("Redirect encountered with redirect: error")
                                if (status in 300..399 && redirectMode == "follow" && !location.isNullOrBlank()) {
                                    if (++redirects > 10) throw IllegalStateException("Too many redirects")
                                    targetUri = targetUri.resolve(location)
                                    continue
                                }
                                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                                val bytes = stream?.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096); var total = 0; while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response exceeds 2 MB"); out.write(buffer, 0, n) }; out.toByteArray() } ?: ByteArray(0)
                                bytesForLog = bytes.size
                                val responseBody = bytes.toString(Charsets.UTF_8)
                                val repeatKey = method + " " + uri.host + uri.rawPath.orEmpty()
                                val seen = tracker.repeated(method, uri.toString())
                                if (seen == 3) bodyPreview = responseBody.take(300)
                                responseJson = JSONObject().put("status", status).put("body", responseBody).put("statusText", connection.responseMessage.orEmpty()).put("url", targetUri.toString()).put("redirected", redirects > 0).put("headers", JSONObject().apply { connection.headerFields.forEach { (key, values) -> if (!key.isNullOrBlank() && !values.isNullOrEmpty()) put(key.lowercase(), values.joinToString(", ")) } }).toString()
                            } finally { connection.disconnect() }
                        }
                        responseJson ?: throw IllegalStateException("No response received")
                    } catch (e: Exception) {
                        errorForLog = safeLogError(e.message) ?: e.javaClass.simpleName
                        JSONObject().put("status", 599).put("body", errorForLog ?: "Network request failed").put("error", errorForLog).toString()
                    } finally {
                        tracker.recordOutcome(statusForLog, errorForLog)
                        recordRequest(provider, NuvioRequestLog(methodForLog, safeUrl, statusForLog, android.os.SystemClock.elapsedRealtime() - startedAt, bytesForLog, errorForLog, bodyPreview))
                        tracker.finished()
                    }
                    if (runActive.get() && !Thread.currentThread().isInterrupted) {
                        val jsId = JSONObject.quote(id)
                        val jsPayload = JSONObject.quote(payload)
                        main.post { if (runActive.get()) web.evaluateJavascript("window.__nexvioFetchDone(" + jsId + "," + jsPayload + ")", null) }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                tracker.finished()
                main.post { if (runActive.get()) onBudgetExceeded(tracker.count, tracker.rejected) }
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
