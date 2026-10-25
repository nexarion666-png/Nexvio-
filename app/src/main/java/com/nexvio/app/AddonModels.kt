package com.nexvio.app
data class AddonResource(val name:String,val types:List<String> = emptyList(),val idPrefixes:List<String> = emptyList()) {
 fun supports(type:String?) = type == null || types.isEmpty() || types.any { it.equals(type,true) }
}
data class AddonCatalog(val type:String,val id:String,val name:String,val extraFields:List<String> = emptyList())
data class AddonManifest(val id:String,val name:String,val version:String,val description:String = "",val resources:List<AddonResource>,val types:List<String>,val idPrefixes:List<String> = emptyList(),val catalogs:List<AddonCatalog> = emptyList(),val manifestVersion:String? = null) {
 fun supportsResource(resource:String,type:String? = null,contentId:String? = null):Boolean =
  (type == null || types.any { it.equals(type,true) }) && resources.any { r -> r.name.equals(resource,true) && r.supports(type) && (contentId == null || (r.idPrefixes.ifEmpty { idPrefixes }.isEmpty() || r.idPrefixes.ifEmpty { idPrefixes }.any(contentId::startsWith))) }
}
data class InstalledAddon(val manifestUrl:String,val manifest:AddonManifest,val enabled:Boolean = true,val installedAtMillis:Long = System.currentTimeMillis(),val lastCheckedAtMillis:Long? = null,val lastSuccessfulConnectionAtMillis:Long? = null,val lastError:String? = null)
internal fun normalizeManifestUrl(value:String):String {
 val uri=java.net.URI(value.trim())
 require(uri.scheme.equals("http",true)||uri.scheme.equals("https",true)) { "Only HTTP and HTTPS manifest URLs are supported." }
 require(!uri.host.isNullOrBlank() && uri.rawUserInfo==null) { "Enter a valid manifest URL without username or password." }
 require(uri.query==null && uri.fragment==null) { "Manifest URLs cannot contain query strings or fragments." }
 val scheme=uri.scheme.lowercase(); val host=uri.host.lowercase()
 val port=if(uri.port == -1 || (scheme=="http" && uri.port==80) || (scheme=="https" && uri.port==443)) "" else ":${uri.port}"
 val path=uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
 return "$scheme://$host$port$path"
}