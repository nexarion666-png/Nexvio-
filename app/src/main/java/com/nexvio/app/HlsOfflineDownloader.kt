package com.nexvio.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Packages finite HLS VOD streams as a local playlist plus downloaded segments.
 * Live streams, byte-range playlists, alternate audio tracks and DRM are rejected explicitly.
 */
internal object HlsOfflineDownloader {
    suspend fun download(url: String, folder: File, headers: Map<String, String>, onConnection: (HttpURLConnection?) -> Unit) {
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not create the HLS download folder.")
        var playlistUrl = url
        var playlist = fetchText(playlistUrl, headers, onConnection)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val lines = playlist.lines()
            var selected: String? = null
            var bandwidth = -1L
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val rate = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val candidate = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim() ?: continue
                if (rate > bandwidth) { bandwidth = rate; selected = URL(URL(playlistUrl), candidate).toString() }
            }
            playlistUrl = selected ?: throw IOException("Vidlink's master playlist has no video variant.")
            playlist = fetchText(playlistUrl, headers, onConnection)
        }
        if (!playlist.startsWith("#EXTM3U")) throw IOException("Vidlink did not return a valid HLS playlist.")
        if (!playlist.contains("#EXT-X-ENDLIST")) throw IOException("This is a live HLS playlist. Only completed video-on-demand streams can be downloaded.")
        if (playlist.contains("#EXT-X-BYTERANGE") || playlist.contains("#EXT-X-PART") || playlist.contains("#EXT-X-PRELOAD-HINT")) throw IOException("This HLS playlist uses partial or byte-range segments that are not supported for offline downloads.")
        if (Regex("TYPE=AUDIO", RegexOption.IGNORE_CASE).containsMatchIn(playlist)) throw IOException("This stream uses a separate audio rendition. Choose a Vidlink source with combined audio and video.")
        if (Regex("METHOD=(SAMPLE-AES|SAMPLE-AES-CTR)", RegexOption.IGNORE_CASE).containsMatchIn(playlist)) throw IOException("This stream uses DRM encryption and cannot be saved for offline playback.")
        val segmentDir = File(folder, "segments").also { it.mkdirs() }
        val keyDir = File(folder, "keys").also { it.mkdirs() }
        val out = StringBuilder()
        var segmentIndex = 0
        var keyIndex = 0
        var mapIndex = 0
        for (raw in playlist.replace("\r", "").lines()) {
            currentCoroutineContext().ensureActive()
            val line = raw.trim()
            if (line.isEmpty()) { out.append(raw).append('\n'); continue }
            if (line.startsWith("#EXT-X-KEY:", true)) {
                val method = Regex("METHOD=([^,]+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim('"') ?: ""
                if (!method.equals("AES-128", true) && !method.equals("NONE", true)) throw IOException("This HLS encryption method is not supported offline.")
                val match = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)
                if (match != null) {
                    val keyFile = File(keyDir, "key" + keyIndex + ".bin")
                    if (!keyFile.exists() || keyFile.length() == 0L) keyFile.writeBytes(fetchBytes(URL(URL(playlistUrl), match.groupValues[1]).toString(), headers, onConnection))
                    out.append(line.replace(match.value, "URI=\"keys/" + keyFile.name + "\"")).append('\n')
                    keyIndex++
                } else out.append(raw).append('\n')
                continue
            }
            if (line.startsWith("#EXT-X-MAP:", true)) {
                val match = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line) ?: throw IOException("HLS initialization map is missing its URI.")
                val mapFile = File(segmentDir, "init" + mapIndex + ".bin")
                if (!mapFile.exists() || mapFile.length() == 0L) mapFile.writeBytes(fetchBytes(URL(URL(playlistUrl), match.groupValues[1]).toString(), headers, onConnection))
                out.append(line.replace(match.value, "URI=\"segments/" + mapFile.name + "\"")).append('\n')
                mapIndex++
                continue
            }
            if (line.startsWith("#")) { out.append(raw).append('\n'); continue }
            val segmentUrl = URL(URL(playlistUrl), line).toString()
            val ext = URL(segmentUrl).path.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "bin"
            val segment = File(segmentDir, "segment" + segmentIndex.toString().padStart(6, '0') + "." + ext)
            if (!segment.exists() || segment.length() == 0L) {
                val bytes = fetchBytes(segmentUrl, headers, onConnection)
                if (bytes.isEmpty()) throw IOException("Vidlink returned an empty HLS segment.")
                segment.writeBytes(bytes)
            }
            out.append("segments/").append(segment.name).append('\n')
            segmentIndex++
        }
        if (segmentIndex == 0) throw IOException("Vidlink's HLS playlist contains no media segments.")
        File(folder, "playlist.m3u8").writeText(out.toString(), Charsets.UTF_8)
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>, callback: (HttpURLConnection?) -> Unit): String =
        String(fetchBytes(url, headers, callback), Charsets.UTF_8)

    private suspend fun fetchBytes(url: String, headers: Map<String, String>, callback: (HttpURLConnection?) -> Unit): ByteArray {
        currentCoroutineContext().ensureActive()
        val connection = URL(url).openConnection() as HttpURLConnection
        callback(connection)
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.setRequestProperty("User-Agent", "Nexvio/1.0")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (key, value) ->
                if (!key.equals("host", true) && !key.equals("content-length", true) && !key.equals("range", true) && !key.equals("accept-encoding", true)) connection.setRequestProperty(key, value)
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Vidlink HLS request failed (HTTP " + status + ").")
            val bytes = connection.inputStream.use { it.readBytes() }
            currentCoroutineContext().ensureActive()
            return bytes
        } finally {
            callback(null)
            connection.disconnect()
        }
    }
}
