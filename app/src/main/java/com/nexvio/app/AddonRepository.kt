package com.nexvio.app
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class AddonRepository(context:Context) {
 private val prefs=context.applicationContext.getSharedPreferences("nexvio_addons",Context.MODE_PRIVATE)
 fun getInstalled():List<InstalledAddon> = runCatching {
  val a=JSONArray(prefs.getString(KEY,"[]"))
  buildList { for(i in 0 until a.length()) runCatching { add(decode(a.getJSONObject(i))) } }
 }.getOrDefault(emptyList())
 suspend fun install(text:String):InstalledAddon = withContext(Dispatchers.IO) {
  val url=normalizeManifestUrl(text); val m=fetchManifest(url); val old=getInstalled()
  require(old.none { runCatching { normalizeManifestUrl(it.manifestUrl) }.getOrDefault(it.manifestUrl)==url }) { "This manifest URL is already installed." }
  require(old.none { it.manifest.id.equals(m.id,true) }) { "An add-on with ID '${m.id}' is already installed." }
  val now=System.currentTimeMillis()
  InstalledAddon(url,m,installedAtMillis=now,lastCheckedAtMillis=now,lastSuccessfulConnectionAtMillis=now).also { save(old+it) }
 }
 suspend fun testConnection(a:InstalledAddon):InstalledAddon = withContext(Dispatchers.IO) {
  val now=System.currentTimeMillis()
  try {
   val m=fetchManifest(a.manifestUrl)
   if(!m.id.equals(a.manifest.id,true)) throw AddonException("The server advertises a different add-on ID (${m.id}).")
   a.copy(manifest=m,lastCheckedAtMillis=now,lastSuccessfulConnectionAtMillis=now,lastError=null).also(::replace)
  } catch(e:Exception) {
   val message=messageFor(e); replace(a.copy(lastCheckedAtMillis=now,lastError=message))
   throw AddonConnectionException(message,a.copy(lastCheckedAtMillis=now,lastError=message))
  }
 }
 fun setEnabled(id:String,enabled:Boolean)=save(getInstalled().map { if(it.manifest.id==id) it.copy(enabled=enabled) else it })
 fun remove(id:String)=save(getInstalled().filterNot { it.manifest.id==id })
 private fun replace(a:InstalledAddon)=save(getInstalled().map { if(it.manifest.id==a.manifest.id) a else it })
 private fun save(list:List<InstalledAddon>) { prefs.edit().putString(KEY,JSONArray().apply { list.forEach { put(encode(it)) } }.toString()).apply() }

 private fun fetchManifest(url:String):AddonManifest {
  val j=try { JSONObject(fetchText(url)) } catch(e:AddonException) { throw e } catch(_:Exception) { throw AddonException("The server did not return a valid JSON manifest.") }
  val mv=j.optString("manifestVersion").takeIf { it.isNotBlank() }
  if(mv!=null && mv !in setOf("1","1.0","1.0.0")) throw AddonException("Unsupported manifest version '$mv'. Supported: 1, 1.0, 1.0.0.")
  val id=j.optString("id").trim(); val name=j.optString("name").trim(); val version=j.optString("version").trim()
  if(!Regex("[A-Za-z0-9._:@-]{1,200}").matches(id)) throw AddonException("The manifest is missing a valid add-on ID.")
  if(name.isBlank()||name.length>160) throw AddonException("The manifest is missing a valid add-on name.")
  if(version.isBlank()||version.length>80) throw AddonException("The manifest is missing a valid add-on version.")
  val types=j.strings("types"); if(types.isEmpty()) throw AddonException("The manifest must advertise content types.")
  val arr=j.optJSONArray("resources") ?: throw AddonException("The manifest must advertise a resources array.")
  val prefixes=j.strings("idPrefixes")
  val resources=buildList {
   for(i in 0 until arr.length()) when(val item=arr.opt(i)) {
    is String -> if(item.isNotBlank()) add(AddonResource(item.trim(),idPrefixes=prefixes))
    is JSONObject -> item.optString("name").trim().takeIf { it.isNotBlank() }?.let { add(AddonResource(it,item.strings("types"),item.strings("idPrefixes").ifEmpty { prefixes })) }
   }
  }
  if(resources.isEmpty()) throw AddonException("The manifest advertises no supported resources.")
  val catalogs=buildList {
   val ca=j.optJSONArray("catalogs") ?: return@buildList
   for(i in 0 until ca.length()) { val c=ca.optJSONObject(i) ?: continue; val t=c.optString("type").trim(); val idc=c.optString("id").trim()
    if(t.isNotBlank()&&idc.isNotBlank()) add(AddonCatalog(t,idc,c.optString("name").ifBlank { idc },c.optJSONArray("extra").names()))
   }
  }
  return AddonManifest(id,name,version,j.optString("description").take(2000),resources,types,prefixes,catalogs,mv)
 }
 private fun fetchText(start:String):String {
  var current=start
  for(hop in 0..MAX_REDIRECTS) {
   val uri=try { URI(current) } catch(_:Exception) { throw AddonException("The manifest URL is malformed.") }
   if(!(uri.scheme.equals("http",true)||uri.scheme.equals("https",true))||uri.host.isNullOrBlank()||uri.rawUserInfo!=null) throw AddonException("Redirect target must be HTTP or HTTPS.")
   val c=(URL(current).openConnection() as HttpURLConnection).apply { connectTimeout=8000;readTimeout=12000;instanceFollowRedirects=false;setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","Nexvio/1.0 Android AddonClient") }
   try {
    val code=c.responseCode
    if(code in 300..399) {
     if(hop==MAX_REDIRECTS) throw AddonException("The manifest server redirected too many times.")
     val next=uri.resolve(c.getHeaderField("Location") ?: throw AddonException("Redirect has no destination.")).toString()
     if(uri.scheme.equals("https",true)&&URI(next).scheme.equals("http",true)) throw AddonException("HTTPS-to-HTTP redirects are blocked.")
     current=next;continue
    }
    if(code !in 200..299) throw AddonException("The manifest server returned HTTP $code.")
    if(c.contentLengthLong>MAX_BYTES) throw AddonException("Manifest exceeds the 1 MiB limit.")
    val out=ByteArrayOutputStream()
    c.inputStream.use { input -> val b=ByteArray(8192);var total=0;while(true){val n=input.read(b);if(n<0)break;total+=n;if(total>MAX_BYTES)throw AddonException("Manifest exceeds the 1 MiB limit.");out.write(b,0,n)} }
    return out.toString(Charsets.UTF_8.name())
   } finally { c.disconnect() }
  }
  throw AddonException("Could not resolve the manifest URL.")
 }
 private fun messageFor(e:Exception):String { val s=e.message.orEmpty();return when { e is AddonException -> s;s.contains("cleartext",true)->"Android's network policy blocked this HTTP connection. Use HTTPS.";s.isNotBlank()->s.take(180);else->"Could not reach the add-on server. Check the URL and connection." } }
 private fun encode(a:InstalledAddon)=JSONObject().apply { put("url",a.manifestUrl);put("enabled",a.enabled);put("installed",a.installedAtMillis);put("checked",a.lastCheckedAtMillis?:JSONObject.NULL);put("success",a.lastSuccessfulConnectionAtMillis?:JSONObject.NULL);put("error",a.lastError?:JSONObject.NULL);put("manifest",encode(a.manifest)) }
 private fun encode(m:AddonManifest)=JSONObject().apply {
  put("id",m.id);put("name",m.name);put("version",m.version);put("description",m.description);put("types",JSONArray(m.types));put("prefixes",JSONArray(m.idPrefixes));put("manifestVersion",m.manifestVersion?:JSONObject.NULL)
  put("resources",JSONArray().apply { m.resources.forEach { r->put(JSONObject().apply { put("name",r.name);put("types",JSONArray(r.types));put("prefixes",JSONArray(r.idPrefixes)) }) } })
  put("catalogs",JSONArray().apply { m.catalogs.forEach { c->put(JSONObject().apply { put("type",c.type);put("id",c.id);put("name",c.name);put("extra",JSONArray(c.extraFields)) }) } })
 }
 private fun decode(a:JSONObject):InstalledAddon {
  val j=a.getJSONObject("manifest")
  val resources=buildList { val r=j.getJSONArray("resources");for(i in 0 until r.length()) r.getJSONObject(i).let { add(AddonResource(it.getString("name"),it.strings("types"),it.strings("prefixes"))) } }
  val catalogs=buildList { val c=j.optJSONArray("catalogs")?:return@buildList;for(i in 0 until c.length()) c.getJSONObject(i).let { add(AddonCatalog(it.getString("type"),it.getString("id"),it.getString("name"),it.optJSONArray("extra").names())) } }
  val m=AddonManifest(j.getString("id"),j.getString("name"),j.getString("version"),j.optString("description"),resources,j.strings("types"),j.strings("prefixes"),catalogs,j.optString("manifestVersion").takeIf { it.isNotBlank()&&it!="null" })
  return InstalledAddon(a.getString("url"),m,a.optBoolean("enabled",true),a.optLong("installed",0),a.longOrNull("checked"),a.longOrNull("success"),a.optString("error").takeIf { it.isNotBlank()&&it!="null" })
 }
 private fun JSONObject.strings(key:String):List<String> { val a=optJSONArray(key)?:return emptyList();return buildList { for(i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add) } }
 private fun JSONArray?.names():List<String> { val a=this?:return emptyList();return buildList { for(i in 0 until a.length()) a.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add) } }
 private fun JSONObject.longOrNull(key:String):Long?=if(!has(key)||isNull(key))null else optLong(key)
 companion object { private const val KEY="installed_addons_json";private const val MAX_BYTES=1024*1024;private const val MAX_REDIRECTS=5 }
}
class AddonException(message:String):IllegalArgumentException(message)
class AddonConnectionException(message:String,val updatedAddon:InstalledAddon):IllegalStateException(message)
