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
        val host = uri.host.orEmpty()
        val port = if (uri.port >= 0) ":" + uri.port else ""
        uri.scheme + "://" + host + port + uri.rawPath.orEmpty().take(240)
    } catch (_: Exception) { address.substringBefore('?').take(300) }

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
    const val ENGINE_VERSION = "engine-2"
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
        val providers = PluginRepositoryStore(context).all().filter { (_, providerName, _) ->
            (providerFilter == null || providerName.equals(providerFilter, ignoreCase = true)) && providerName !in disabledProviders
        }
            .filter { it.enabled }
            .flatMap { repository ->
                repository.providers.filter { it.enabled }
                    .map { provider -> Triple(repository.name, provider.name, provider.fileUrl) }
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
                        val js = "(function(){var module={exports:{}},exports=module.exports;var __fetchCallbacks={};window.__nexvioUnsupportedModule=function(n){NexvioBridge.unsupportedModule(n)};" +
                            "(function(){var module,exports;" + cryptoJsSource + "}).call(window);" +
                            "if(!window.CryptoJS||!CryptoJS.AES||!CryptoJS.MD5||!CryptoJS.SHA256||!CryptoJS.HmacSHA256||!CryptoJS.enc||!CryptoJS.enc.Base64||!CryptoJS.enc.Utf8||!CryptoJS.enc.Hex)throw new Error('CryptoJS required algorithms are unavailable');" +
                            "var __cryptoProbe=CryptoJS.AES.encrypt('nexvio','nexvio-runtime-self-test').toString();if(CryptoJS.AES.decrypt(__cryptoProbe,'nexvio-runtime-self-test').toString(CryptoJS.enc.Utf8)!=='nexvio'||!CryptoJS.MD5('nexvio').toString()||!CryptoJS.SHA256('nexvio').toString()||!CryptoJS.HmacSHA256('nexvio','key').toString())throw new Error('CryptoJS runtime self-test failed');" +
                            "window.global=window;try{globalThis.global=window}catch(e){};if(!window.process)window.process={env:{},version:'',platform:'android',nextTick:function(fn){setTimeout(fn,0)}};" +
                            "if(!window.TextEncoder)window.TextEncoder=function(){this.encode=function(s){var r=unescape(encodeURIComponent(String(s))),a=new Uint8Array(r.length);for(var i=0;i<r.length;i++)a[i]=r.charCodeAt(i);return a}};" +
                            "if(!window.TextDecoder)window.TextDecoder=function(){this.decode=function(a){var r='';for(var i=0;i<a.length;i++)r+=String.fromCharCode(a[i]);return decodeURIComponent(escape(r))}};" +
                            "if(!window.setTimeout)window.setTimeout=function(fn){return window.setInterval(fn,0)};if(!window.clearTimeout)window.clearTimeout=function(id){window.clearInterval(id)};if(!window.setInterval)window.setInterval=function(fn){fn();return 0};if(!window.clearInterval)window.clearInterval=function(){};" +
                            "if(!window.URL||!window.URLSearchParams)throw new Error('WebView URL APIs are required');" +
                            "if(!window.AbortController)window.AbortController=function(){var ls=[];this.signal={aborted:false,addEventListener:function(n,f){if(n==='abort')ls.push(f)},removeEventListener:function(n,f){ls=ls.filter(function(x){return x!==f})}};this.abort=function(){if(this.signal.aborted)return;this.signal.aborted=true;ls.slice().forEach(function(f){try{f()}catch(e){}});ls=[]}};" +
                            "if(!window.Buffer)window.Buffer={from:function(v,enc){enc=String(enc||'utf8').toLowerCase();var bytes;if(v instanceof Uint8Array)bytes=v;else if(Array.isArray(v))bytes=new Uint8Array(v);else if(enc==='base64'){var r=atob(String(v));bytes=new Uint8Array(r.length);for(var i=0;i<r.length;i++)bytes[i]=r.charCodeAt(i)}else if(enc==='hex'){var h=String(v);bytes=new Uint8Array(Math.floor(h.length/2));for(var j=0;j<bytes.length;j++)bytes[j]=parseInt(h.substr(j*2,2),16)}else bytes=new TextEncoder().encode(String(v));return {length:bytes.length,buffer:bytes.buffer,toString:function(out){out=String(out||'utf8').toLowerCase();if(out==='hex')return Array.prototype.map.call(bytes,function(b){return ('0'+b.toString(16)).slice(-2)}).join('');if(out==='base64'){var r='';for(var k=0;k<bytes.length;k++)r+=String.fromCharCode(bytes[k]);return btoa(r)}return new TextDecoder().decode(bytes)}}};" +
                            "window.__nexvioFetchDone=function(id,payload){var cb=__fetchCallbacks[id];if(!cb)return;delete __fetchCallbacks[id];if(cb.signal&&cb.abortFn&&cb.signal.removeEventListener)cb.signal.removeEventListener('abort',cb.abortFn);try{var d=JSON.parse(payload);if(d.status===599){cb.reject(new TypeError('Network request failed'));return}var hs=d.headers||{};cb.resolve({ok:d.status>=200&&d.status<300,status:d.status,statusText:d.statusText||'',url:d.url||'',headers:{get:function(n){n=String(n||'').toLowerCase();return Object.prototype.hasOwnProperty.call(hs,n)?hs[n]:null}},text:function(){return Promise.resolve(d.body)},json:function(){try{return Promise.resolve(JSON.parse(d.body))}catch(e){return Promise.reject(e)}},clone:function(){return this},arrayBuffer:function(){return Promise.resolve(new TextEncoder().encode(d.body).buffer)},blob:function(){return Promise.resolve(new Blob([d.body]))}})}catch(e){cb.reject(e)}};" +
                            "window.fetch=function(input,opts){opts=opts||{};return new Promise(function(resolve,reject){var id=String(Date.now())+Math.random().toString(36).slice(2),cb={resolve:resolve,reject:reject,signal:opts.signal,abortFn:null};if(opts.signal&&opts.signal.aborted){var er=new Error('The operation was aborted');er.name='AbortError';reject(er);return}if(opts.signal&&opts.signal.addEventListener){cb.abortFn=function(){delete __fetchCallbacks[id];var er=new Error('The operation was aborted');er.name='AbortError';reject(er)};opts.signal.addEventListener('abort',cb.abortFn,{once:true})}__fetchCallbacks[id]=cb;NexvioBridge.request(id,String(input),JSON.stringify(opts))})};" +
                            "var __axios=function(config){config=config||{};var method=String(config.method||'GET').toUpperCase();var url=config.url||'';var headers=config.headers||{};var body=config.data;if(body&&typeof body!=='string')body=JSON.stringify(body);return window.fetch(url,{method:method,headers:headers,body:method==='GET'||method==='HEAD'?undefined:body}).then(function(r){return r.text().then(function(t){var data=t;try{data=JSON.parse(t)}catch(e){};return {data:data,status:r.status,statusText:'',headers:{},config:config,request:null}})}).then(function(r){if(r.status<200||r.status>=300){var e=new Error('Request failed with status code '+r.status);e.response=r;throw e}return r})};" +
                            "__axios.get=function(url,config){return __axios(Object.assign({},config||{},{url:url,method:'GET'}))};" +
                            "__axios.post=function(url,data,config){return __axios(Object.assign({},config||{},{url:url,data:data,method:'POST'}))};" +
                            "__axios.head=function(url,config){return __axios(Object.assign({},config||{},{url:url,method:'HEAD'}))};" +
                            """var __cheerio={load:function(html){
var doc=new DOMParser().parseFromString(String(html||''),'text/html');
function isSet(x){return x&&typeof x.toArray==='function'}
function raw(x){return isSet(x)?x.toArray():x&&x.nodeType?[x]:[]}
function matches(el,sel,index){if(typeof sel==='function')return !!sel.call(el,index,el);if(isSet(sel)){return raw(sel).indexOf(el)>=0}if(sel&&sel.nodeType)return el===sel;if(typeof sel!=='string')return false;var s=sel.trim(),m=s.match(/:(first|last|eq\\((-?\\d+)\\))$/);if(m){var all=select(el.parentElement||el.ownerDocument,s.replace(/:(first|last|eq\\(-?\\d+\\))$/,'' )||'*');var idx=all.indexOf(el);return m[1]==='first'?idx===0:m[1]==='last'?idx===all.length-1:idx===(parseInt(m[2],10)<0?all.length+parseInt(m[2],10):parseInt(m[2],10))}var contains=s.match(/:contains\\((['"])(.*?)\\1\\)/);if(contains){s=s.replace(/:contains\\((['"])(.*?)\\1\\)/g,'')||'*';return !!(el.matches&&el.matches(s))&&(el.textContent||'').indexOf(contains[2])>=0}try{return !!(el.matches&&el.matches(s))}catch(e){return false}}
function select(root,sel){if(typeof sel!=='string')return raw(sel);var s=sel.trim(),first=s.match(/:(first|last|eq\\((-?\\d+)\\))$/);if(first)s=s.replace(/:(first|last|eq\\(-?\\d+\\))$/,'');var contains=s.match(/:contains\\((['"])(.*?)\\1\\)/);if(contains)s=s.replace(/:contains\\((['"])(.*?)\\1\\)/g,'')||'*';var found=[];try{found=Array.prototype.slice.call(root.querySelectorAll(s||'*'))}catch(e){return []}if(contains)found=found.filter(function(n){return (n.textContent||'').indexOf(contains[2])>=0});if(first){var n=parseInt(first[2],10);if(first[1]==='first')found=found.slice(0,1);else if(first[1]==='last')found=found.slice(-1);else found=found.slice(n<0?Math.max(0,found.length+n):n,(n<0?Math.max(0,found.length+n):n)+1)}return found}
function wrap(input,prev){var nodes=Array.prototype.slice.call(input||[]).filter(Boolean);var api={length:nodes.length,each:function(fn){nodes.forEach(function(n,i){fn.call(n,i,n)});return api},map:function(fn){return wrap(nodes.map(function(n,i){return fn.call(n,i,n)}).reduce(function(a,x){return a.concat(raw(x).length?raw(x):x==null?[]:[x])},[]),api)},get:function(i){return i==null?nodes.slice():nodes[i<0?nodes.length+i:i]},toArray:function(){return nodes.slice()},find:function(sel){var a=[];nodes.forEach(function(n){var descendants=Array.prototype.slice.call(n.querySelectorAll?n.querySelectorAll('*'):[]);if(typeof sel==='function'||isSet(sel)||sel&&sel.nodeType)a=a.concat(descendants.filter(function(x,i){return matches(x,sel,i)}));else a=a.concat(select(n,sel))});return wrap(a,api)},filter:function(sel){return wrap(nodes.filter(function(n,i){return matches(n,sel,i)}),api)},not:function(sel){return wrap(nodes.filter(function(n,i){return !matches(n,sel,i)}),api)},has:function(sel){return wrap(nodes.filter(function(n,i){return typeof sel==='string'?select(n,sel).length>0:raw(sel).some(function(x){return n.contains&&n.contains(x)})}),api)},is:function(sel){return nodes.some(function(n,i){return matches(n,sel,i)})},first:function(){return wrap(nodes.slice(0,1),api)},last:function(){return wrap(nodes.slice(-1),api)},eq:function(i){var n=api.get(i);return wrap(n?[n]:[],api)},slice:function(a,b){return wrap(nodes.slice(a,b),api)},children:function(sel){var a=[];nodes.forEach(function(n){a=a.concat(Array.prototype.slice.call(n.children||[]))});return wrap(sel==null?a:a.filter(function(n,i){return matches(n,sel,i)}),api)},parent:function(){return wrap(nodes.map(function(n){return n.parentElement}).filter(Boolean),api)},closest:function(sel){var a=[];nodes.forEach(function(n,i){var p=n;while(p&&p.nodeType===1&&!matches(p,sel,i))p=p.parentElement;if(p&&a.indexOf(p)<0)a.push(p)});return wrap(a,api)},next:function(){return wrap(nodes.map(function(n){return n.nextElementSibling}).filter(Boolean),api)},prev:function(){return wrap(nodes.map(function(n){return n.previousElementSibling}).filter(Boolean),api)},nextAll:function(){var a=[];nodes.forEach(function(n){for(var p=n.nextElementSibling;p;p=p.nextElementSibling)a.push(p)});return wrap(a,api)},prevAll:function(){var a=[];nodes.forEach(function(n){for(var p=n.previousElementSibling;p;p=p.previousElementSibling)a.push(p)});return wrap(a,api)},siblings:function(){var a=[];nodes.forEach(function(n){Array.prototype.forEach.call(n.parentElement&&n.parentElement.children||[],function(x){if(x!==n&&a.indexOf(x)<0)a.push(x)})});return wrap(a,api)},hasClass:function(name){return nodes.some(function(n){return n.classList&&n.classList.contains(name)})},data:function(name,value){var key='data-'+String(name).replace(/[A-Z]/g,function(x){return '-'+x.toLowerCase()});if(arguments.length>1){nodes.forEach(function(n){if(n.setAttribute)n.setAttribute(key,String(value))});return api}var n=nodes[0];return n&&n.getAttribute?n.getAttribute(key):undefined},attr:function(k,v){if(arguments.length>1){nodes.forEach(function(n){if(n.setAttribute)n.setAttribute(k,String(v))});return api}return nodes[0]&&nodes[0].getAttribute?nodes[0].getAttribute(k):undefined},prop:function(k,v){if(arguments.length>1){nodes.forEach(function(n){n[k]=v});return api}return nodes[0]?nodes[0][k]:undefined},text:function(v){if(arguments.length){nodes.forEach(function(n){n.textContent=typeof v==='function'?v.call(n,nodes.indexOf(n),n.textContent):v});return api}return nodes.map(function(n){return n.textContent||''}).join('')},html:function(v){if(arguments.length){nodes.forEach(function(n){n.innerHTML=typeof v==='function'?v.call(n,nodes.indexOf(n),n.innerHTML):v});return api}return nodes[0]&&nodes[0].innerHTML!=null?nodes[0].innerHTML:null},contents:function(){var a=[];nodes.forEach(function(n){a=a.concat(Array.prototype.slice.call(n.childNodes||[]))});return wrap(a,api)},index:function(){if(!nodes[0]||!nodes[0].parentElement)return -1;return Array.prototype.indexOf.call(nodes[0].parentElement.children,nodes[0])},end:function(){return prev||wrap([],null)},clone:function(){return wrap(nodes.map(function(n){return n.cloneNode(true)}),api)},add:function(sel){var extra=typeof sel==='string'?select(doc,sel):raw(sel);return wrap(nodes.concat(extra).filter(function(n,i,a){return a.indexOf(n)===i}),api)},append:function(){return api},prepend:function(){return api},remove:function(){nodes.forEach(function(n){if(n&&n.remove)n.remove()});return api},val:function(v){if(arguments.length){nodes.forEach(function(n){n.value=v});return api}return nodes[0]&&nodes[0].value||''},toString:function(){return api.html()||api.text()}};return api}
function $(selector){if(typeof selector==='string'){if(selector.trim().charAt(0)==='<'){var tmp=new DOMParser().parseFromString(selector,'text/html');return wrap(Array.prototype.slice.call(tmp.body.children))}return wrap(select(doc,selector))}return wrap(raw(selector))}$.html=function(){return doc.documentElement.outerHTML};$.root=function(){return wrap([doc])};$.contains=function(a,b){return !!(a&&a.contains&&a.contains(b))};return $}};""" +
                            "var __console={log:function(){},info:function(){},warn:function(){},error:function(){}};" +
                            "var require=function(n){if(n==='axios')return __axios;if(n==='console')return __console;if(n==='url')return {URL:URL,URLSearchParams:URLSearchParams};if(n==='cheerio'||n==='cheerio-without-node-native')return __cheerio;if(n==='crypto-js')return window.CryptoJS;if(n.indexOf('crypto-js/')===0){var p=n.substring(10).split(/[-/]/),o=window.CryptoJS;for(var i=0;i<p.length&&o!=null;i++){var k=p[i],key=k==='aes'?'AES':k==='md5'?'MD5':k==='sha256'?'SHA256':k==='hmac'?'Hmac':k==='utf8'?'Utf8':k==='hex'?'Hex':k==='base64'?'Base64':k==='cbc'?'CBC':k==='pkcs7'?'Pkcs7':k==='sha1'?'SHA1':k==='sha512'?'SHA512':k.charAt(0).toUpperCase()+k.slice(1);if(k==='hmac'&&p[i+1]){i++;key='Hmac'+p[i].toUpperCase()+p[i].slice(1)}o=o[key]}if(o!==undefined&&o!==null)return o}window.__nexvioUnsupportedModule(String(n));throw new Error('Unsupported module: '+n+' (Nexvio supports axios, cheerio, console, url and crypto-js)')};" +
                            "try{(new Function('module','exports','require','global'," + src + "))(module,exports,require,window);" +
                            "var fn=module.exports&&module.exports.getStreams;if(typeof fn!=='function')throw new Error('Missing getStreams export');" +
                            "Promise.resolve(fn(" + id + "," + type + "," + s + "," + e + ")).then(function(v){" +
                            "NexvioBridge.finish(JSON.stringify({ok:true,streams:Array.isArray(v)?v:[]}));" +
                            "}).catch(function(x){NexvioBridge.finish(JSON.stringify({ok:false,error:String(x)}));});" +
                            "}catch(x){NexvioBridge.finish(JSON.stringify({ok:false,error:String(x)}));}})();"
                        view.evaluateJavascript(js, null)
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
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36")
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
                    errorForLog = e.message ?: e.javaClass.simpleName
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
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36")
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
