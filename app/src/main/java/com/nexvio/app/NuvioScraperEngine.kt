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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume

internal data class NuvioDiscoveredStream(val providerName: String, val title: String, val url: String, val quality: String, val headers: Map<String, String> = emptyMap())

/**
 * Runs compatible Nuvio CommonJS providers in a disposable JavaScript WebView.
 * This is intentionally a restricted adapter, not a full Node/Hermes clone:
 * Node built-ins and arbitrary require() modules are unavailable.
 */
internal object NuvioScraperEngine {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)

    suspend fun discover(context: Context, item: TmdbTitle, season: Int, episode: Int): List<NuvioDiscoveredStream> {
        val prefs = context.getSharedPreferences("nexvio_addons", Context.MODE_PRIVATE)
        val saved = JSONArray(prefs.getString("installed", "[]") ?: "[]")
        val repositories = (0 until saved.length()).mapNotNull { i ->
            val obj = saved.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("manifestUrl")
            if (!obj.optBoolean("enabled", true) || !(url.startsWith("https://") || url.startsWith("http://"))) null
            else obj.optString("name", "Repository") to url
        }
        val results = mutableListOf<NuvioDiscoveredStream>()
        for ((repoName, manifestUrl) in repositories) {
            val manifest = try { JSONObject(fetchText(manifestUrl, MAX_BYTES)) } catch (_: Exception) { continue }
            val scrapers = manifest.optJSONArray("scrapers") ?: continue
            val base = try {
                val uri = URI(manifestUrl)
                uri.scheme + "://" + uri.rawAuthority + uri.path.orEmpty().substringBeforeLast('/', "") + "/"
            } catch (_: Exception) { continue }
            for (i in 0 until minOf(scrapers.length(), 30)) {
                val scraper = scrapers.optJSONObject(i) ?: continue
                if (!scraper.optBoolean("enabled", true)) continue
                val types = scraper.optJSONArray("supportedTypes")
                val type = if (item.mediaType == "movie") "movie" else "tv"
                if (types != null && (0 until types.length()).none { types.optString(it).equals(type, true) }) continue
                val filename = scraper.optString("filename")
                if (filename.isBlank()) continue
                val scriptUrl = try { URL(URL(manifestUrl), filename).toString() } catch (_: Exception) { base + filename }
                val script = try { fetchText(scriptUrl, MAX_BYTES) } catch (_: Exception) { continue }
                val name = scraper.optString("name").ifBlank { scraper.optString("id").ifBlank { repoName } }
                try { results += execute(context, name, script, item, season, episode) } catch (_: Exception) { }
                if (results.size >= 50) return results.distinctBy { it.url }.take(50)
            }
        }
        return results.distinctBy { it.url }.take(50)
    }

    private suspend fun execute(context: Context, name: String, source: String, item: TmdbTitle, season: Int, episode: Int): List<NuvioDiscoveredStream> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context)
                val bridge = Bridge(web)
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
                        val js = "(function(){var module={exports:{}},exports=module.exports;var __fetchCallbacks={};" +
                            "window.__nexvioFetchDone=function(id,payload){var cb=__fetchCallbacks[id];if(!cb)return;delete __fetchCallbacks[id];try{var d=JSON.parse(payload);cb.resolve({ok:d.status>=200&&d.status<300,status:d.status,text:function(){return Promise.resolve(d.body)},json:function(){try{return Promise.resolve(JSON.parse(d.body))}catch(e){return Promise.reject(e)}}})}catch(e){cb.reject(e)}};" +
                            "window.fetch=function(input,opts){return new Promise(function(resolve,reject){var id=String(Date.now())+Math.random().toString(36).slice(2);__fetchCallbacks[id]={resolve:resolve,reject:reject};NexvioBridge.request(id,String(input),JSON.stringify(opts||{}))})};" +
                            "var require=function(n){throw new Error('Unsupported module: '+n)};" +
                            "try{(new Function('module','exports','require'," + src + "))(module,exports,require);" +
                            "var fn=module.exports&&module.exports.getStreams;if(typeof fn!=='function')throw new Error('Missing getStreams export');" +
                            "Promise.resolve(fn(" + id + "," + type + "," + s + "," + e + ")).then(function(v){" +
                            "NexvioBridge.finish(JSON.stringify({ok:true,streams:Array.isArray(v)?v:[]}));" +
                            "}).catch(function(x){NexvioBridge.finish(JSON.stringify({ok:false,error:String(x)}));});" +
                            "}catch(x){NexvioBridge.finish(JSON.stringify({ok:false,error:String(x)}));}})();"
                        view.evaluateJavascript(js, null)
                    }
                }
                bridge.done = { raw ->
                    if (continuation.isActive) {
                        val parsed = try {
                            val root = JSONObject(raw)
                            val arr = root.optJSONArray("streams") ?: JSONArray()
                            if (!root.optBoolean("ok")) JSONArray() else arr
                        } catch (_: Exception) { JSONArray() }
                        val streams = (0 until parsed.length()).mapNotNull { i ->
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
                        continuation.resume(streams)
                    }
                    main.post { web.stopLoading(); web.removeJavascriptInterface("NexvioBridge"); web.destroy() }
                }
                web.addJavascriptInterface(bridge, "NexvioBridge")
                continuation.invokeOnCancellation { main.post { web.stopLoading(); web.destroy() } }
                main.postDelayed({
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                        web.stopLoading(); web.removeJavascriptInterface("NexvioBridge"); web.destroy()
                    }
                }, 25000)
                web.loadDataWithBaseURL("https://nexvio.invalid/", "<html><body></body></html>", "text/html", "UTF-8", null)
            }
        }

    private class Bridge(private val web: WebView) {
        @Volatile var done: ((String) -> Unit)? = null
        @JavascriptInterface fun finish(result: String) { done?.invoke(result.take(512 * 1024)) }

        @JavascriptInterface
        fun request(id: String, address: String, optionsText: String) {
            pool.execute {
                val payload = try {
                    val uri = URI(address.trim())
                    require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                        !uri.host.isNullOrBlank() && uri.userInfo == null)
                    val host = uri.host.lowercase()
                    val ip = java.net.InetAddress.getByName(host)
                    require(host != "localhost" && !host.endsWith(".local") && !ip.isAnyLocalAddress &&
                        !ip.isLoopbackAddress && !ip.isLinkLocalAddress && !ip.isSiteLocalAddress) {
                        "Requests to local/private hosts are blocked."
                    }
                    val options = JSONObject(optionsText)
                    val method = options.optString("method", "GET").uppercase()
                    require(method == "GET" || method == "POST" || method == "HEAD")
                    val connection = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 8000
                        connection.readTimeout = 10000
                        connection.instanceFollowRedirects = true
                        connection.requestMethod = method
                        connection.setRequestProperty("User-Agent", "Nexvio/1.0 Android")
                        val headers = options.optJSONObject("headers")
                        if (headers != null) {
                            val keys = headers.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                if (!key.equals("host", true) && !key.equals("content-length", true))
                                    connection.setRequestProperty(key.take(80), headers.optString(key).take(2000))
                            }
                        }
                        val body = options.optString("body")
                        if (method == "POST" && body.isNotBlank()) {
                            connection.doOutput = true
                            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8).take(256 * 1024).toByteArray()) }
                        }
                        val status = connection.responseCode
                        val stream = if (status >= 400) connection.errorStream else connection.inputStream
                        val response = stream?.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            var total = 0
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response exceeds 2 MB")
                                out.write(buffer, 0, n)
                            }
                            out.toString("UTF-8")
                        }.orEmpty()
                        JSONObject().put("status", status).put("body", response).toString()
                    } finally { connection.disconnect() }
                } catch (e: Exception) {
                    JSONObject().put("status", 599).put("body", e.message ?: "Network request failed").toString()
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
            c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Nexvio/1.0 Android")
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
