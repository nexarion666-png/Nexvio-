package com.nexvio.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves a finite HLS VOD stream as a local master playlist and its media segments.
 * The selected video variant and its default external audio rendition are packaged
 * together so offline playback retains audio.
 */
internal object HlsOfflineDownloader {
    suspend fun download(
        url: String,
        folder: File,
        headers: Map<String, String>,
        preferredVideoHeight: Int?,
        onConnection: (HttpURLConnection?) -> Unit,
        onProgress: (Long, Long) -> Unit
    ) {
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not create the HLS download folder.")
        var downloadedBytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        onProgress(downloadedBytes, -1L)

        val masterResponse = fetchText(url, headers, onConnection)
        val masterUrl = masterResponse.finalUrl
        val masterText = masterResponse.text
        if (!masterText.trimStart().startsWith("#EXTM3U")) {
            throw IOException("Vixsrc did not return a valid HLS playlist. Refresh the stream and try again.")
        }

        val lines = masterText.replace("\r", "").lines()
        val variants = mutableListOf<Variant>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:", true)) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim() ?: continue
            val height = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val audioGroup = attribute(line, "AUDIO")
            variants += Variant(line, URL(URL(masterUrl), uri).toString(), height, audioGroup)
        }

        if (variants.isEmpty()) {
            val mediaFile = File(folder, "playlist.m3u8")
            downloadedBytes += packageMediaPlaylist(masterUrl, masterText, mediaFile, folder, headers, onConnection, -1L, downloadedBytes, onProgress)
            val actualFolderBytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            onProgress(actualFolderBytes, actualFolderBytes)
            return
        }

        val selected = selectVariant(variants, preferredVideoHeight)
        val audioGroup = selected.audioGroup
        val audioRenditions = lines.filter { it.trim().startsWith("#EXT-X-MEDIA:", true) }
            .filter { attribute(it, "TYPE").equals("AUDIO", true) && attribute(it, "GROUP-ID") == audioGroup }
        val selectedAudio = audioRenditions.firstOrNull { attribute(it, "DEFAULT").equals("YES", true) }
            ?: audioRenditions.firstOrNull { attribute(it, "AUTOSELECT").equals("YES", true) }
            ?: audioRenditions.firstOrNull()

        val variantResponse = fetchText(selected.url, headers, onConnection)
        val variantPlaylistUrl = variantResponse.finalUrl
        val variantPlaylistText = variantResponse.text
        val videoDurationSeconds = playlistDurationSeconds(variantPlaylistText)
        val videoBandwidth = attribute(selected.tag, "AVERAGE-BANDWIDTH")?.toLongOrNull()
            ?: attribute(selected.tag, "BANDWIDTH")?.toLongOrNull()
        val audioBandwidth = selectedAudio?.let {
            attribute(it, "AVERAGE-BANDWIDTH")?.toLongOrNull()
                ?: attribute(it, "BANDWIDTH")?.toLongOrNull()
        } ?: 0L
        val bitrateEstimate = if (videoDurationSeconds > 0.0 && videoBandwidth != null && videoBandwidth > 0L) {
            (((videoBandwidth + audioBandwidth).toDouble() * videoDurationSeconds) / 8.0).toLong().coerceAtLeast(1L)
        } else -1L

        // First try to measure every selected video/audio segment using HEAD or a
        // one-byte Range request. If the CDN exposes resource lengths, this is the
        // exact media payload size before the download begins.
        val audioPlaylistUrl = selectedAudio?.let { audio ->
            attribute(audio, "URI")?.takeIf { it.isNotBlank() }?.let { URL(URL(masterUrl), it).toString() }
        }
        val audioPlaylistResponse = audioPlaylistUrl?.let { fetchText(it, headers, onConnection) }
        val resolvedAudioPlaylistUrl = audioPlaylistResponse?.finalUrl ?: audioPlaylistUrl
        val audioPlaylistText = audioPlaylistResponse?.text
        // Vixsrc CDN segment URLs can be signed and rate-limited. Probing every
        // segment with HEAD and then Range before downloading can leave the UI
        // appearing stuck for minutes. Use the playlist bitrate estimate there;
        // actual bytes are still reported as each segment is saved.
        val vixsrcSource = headers.any { (key, value) ->
            (key.equals("Referer", true) || key.equals("Origin", true)) &&
                value.contains("vixsrc.to", ignoreCase = true)
        } || runCatching { URL(url).host.contains("vixsrc.to", ignoreCase = true) }.getOrDefault(false)
        val videoExactBytes = if (vixsrcSource) -1L else playlistResourceBytes(variantPlaylistUrl, variantPlaylistText, headers)
        val audioExactBytes = if (!vixsrcSource && audioPlaylistUrl != null && audioPlaylistText != null) {
            playlistResourceBytes(resolvedAudioPlaylistUrl!!, audioPlaylistText, headers)
        } else if (audioPlaylistUrl == null) 0L else -1L
        val exactTotalBytes = if (videoExactBytes >= 0L && audioExactBytes >= 0L) {
            videoExactBytes + audioExactBytes
        } else -1L
        val totalBytes = exactTotalBytes.takeIf { it > 0L } ?: bitrateEstimate
        onProgress(downloadedBytes, totalBytes)

        val variantFile = File(folder, "variant/playlist.m3u8")
        variantFile.parentFile?.mkdirs()
        downloadedBytes += packageMediaPlaylist(
            variantPlaylistUrl, variantPlaylistText, variantFile,
            variantFile.parentFile!!, headers, onConnection, totalBytes, downloadedBytes, onProgress
        )

        var localAudioLine: String? = null
        if (selectedAudio != null) {
            val audioUri = attribute(selectedAudio, "URI")
            if (!audioUri.isNullOrBlank()) {
                val audioUrl = URL(URL(masterUrl), audioUri).toString()
                val audioFile = File(folder, "audio/playlist.m3u8")
                audioFile.parentFile?.mkdirs()
                downloadedBytes += packageMediaPlaylist(
                    resolvedAudioPlaylistUrl ?: audioUrl, audioPlaylistText ?: fetchText(audioUrl, headers, onConnection).text, audioFile,
                    audioFile.parentFile!!, headers, onConnection, totalBytes, downloadedBytes, onProgress
                )
                localAudioLine = selectedAudio.replace(Regex("URI=\"[^\"]*\"", RegexOption.IGNORE_CASE), "URI=\"audio/playlist.m3u8\"")
            } else {
                localAudioLine = selectedAudio
            }
        }

        val output = StringBuilder()
        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:", true) -> {
                    if (line == selected.tag) {
                        output.append(raw).append('\n')
                        output.append("variant/playlist.m3u8\n")
                    }
                }
                line.startsWith("#EXT-X-MEDIA:", true) -> {
                    if (selectedAudio != null && line == selectedAudio.trim()) {
                        output.append(localAudioLine ?: selectedAudio).append('\n')
                    }
                }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF:", true) -> Unit
                line.startsWith("#EXT-X-SESSION-KEY:", true) ->
                    throw IOException("This HLS stream uses session-level encryption that cannot be saved for offline playback.")
                line.isNotEmpty() && !line.startsWith("#") -> Unit
                else -> if (line.isNotEmpty()) output.append(raw).append('\n')
            }
        }
        if (!output.contains("#EXTM3U")) throw IOException("Could not build the offline HLS playlist.")
        File(folder, "playlist.m3u8").writeText(output.toString(), Charsets.UTF_8)
        // Once every video/audio segment is saved, report the exact packaged size.
        val actualFolderBytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        onProgress(actualFolderBytes, actualFolderBytes)
    }

    /**
     * Returns the exact byte count for all media resources referenced by a finite
     * HLS playlist, or -1 when even one resource does not disclose its length.
     */
    private suspend fun playlistResourceBytes(
        playlistUrl: String,
        playlistText: String,
        headers: Map<String, String>
    ): Long {
        if (!playlistText.trimStart().startsWith("#EXTM3U") || !playlistText.contains("#EXT-X-ENDLIST")) return -1L
        val resources = mutableListOf<String>()
        for (raw in playlistText.replace("\\r", "").lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-KEY:", true) || line.startsWith("#EXT-X-MAP:", true)) {
                val uri = attribute(line, "URI") ?: continue
                if (uri.isNotBlank()) resources += URL(URL(playlistUrl), uri).toString()
            } else if (!line.startsWith("#")) {
                resources += URL(URL(playlistUrl), line).toString()
            }
        }
        if (resources.isEmpty()) return -1L
        var total = 0L
        for (resource in resources.distinct()) {
            val size = probeResourceSize(resource, headers)
            if (size < 0L) return -1L
            total += size
        }
        return total
    }

    private suspend fun probeResourceSize(url: String, headers: Map<String, String>): Long {
        currentCoroutineContext().ensureActive()
        val head = open(url, headers).apply { requestMethod = "HEAD" }
        try {
            val code = head.responseCode
            if (code in 200..299 && head.contentLengthLong >= 0L) return head.contentLengthLong
        } catch (_: Exception) {
            // Some CDNs reject HEAD; try a one-byte range request below.
        } finally {
            head.disconnect()
        }

        currentCoroutineContext().ensureActive()
        val ranged = open(url, headers).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            val code = ranged.responseCode
            val contentRange = ranged.getHeaderField("Content-Range")
            val total = contentRange?.substringAfterLast('/')?.toLongOrNull()
            if (code == 206 && total != null && total >= 0L) return total
            if (code in 200..299 && ranged.contentLengthLong >= 0L) return ranged.contentLengthLong
            return -1L
        } catch (_: Exception) {
            return -1L
        } finally {
            ranged.disconnect()
        }
    }

    private fun playlistDurationSeconds(text: String): Double =
        Regex("#EXTINF:([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
            .findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

    private data class Variant(val tag: String, val url: String, val height: Int?, val audioGroup: String?)

    private fun selectVariant(variants: List<Variant>, preferredHeight: Int?): Variant {
        if (preferredHeight == null) return variants.maxByOrNull { it.height ?: 0 } ?: variants.first()
        return variants.firstOrNull { it.height == preferredHeight }
            ?: variants.filter { (it.height ?: 0) <= preferredHeight }.maxByOrNull { it.height ?: 0 }
            ?: variants.minByOrNull { it.height ?: Int.MAX_VALUE }
            ?: variants.first()
    }

    private fun attribute(line: String, name: String): String? =
        Regex("(?:^|[, :])" + Regex.escape(name) + "=(?:\"([^\"]*)\"|([^,]*))", RegexOption.IGNORE_CASE)
            .find(line)?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.trim() }

    private suspend fun packageMediaPlaylist(
        playlistUrl: String,
        playlistText: String,
        localPlaylist: File,
        localFolder: File,
        headers: Map<String, String>,
        onConnection: (HttpURLConnection?) -> Unit,
        estimatedTotalBytes: Long,
        initialBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): Long {
        if (!playlistText.trimStart().startsWith("#EXTM3U")) throw IOException("The HLS media playlist is invalid.")
        if (!playlistText.contains("#EXT-X-ENDLIST")) throw IOException("This is a live HLS stream. Only completed movies and episodes can be downloaded.")
        if (playlistText.contains("#EXT-X-BYTERANGE") || playlistText.contains("#EXT-X-PART") || playlistText.contains("#EXT-X-PRELOAD-HINT")) {
            throw IOException("This HLS stream uses partial or byte-range segments that are not supported for offline downloads.")
        }
        if (Regex("METHOD=(SAMPLE-AES|SAMPLE-AES-CTR|AES-256)", RegexOption.IGNORE_CASE).containsMatchIn(playlistText)) {
            throw IOException("This HLS stream uses DRM encryption that cannot be saved for offline playback.")
        }

        val segmentDir = File(localFolder, "segments").also { it.mkdirs() }
        val keyDir = File(localFolder, "keys").also { it.mkdirs() }
        val output = StringBuilder()
        var segmentIndex = 0
        var keyIndex = 0
        var mapIndex = 0
        var addedBytes = 0L
        var progressBytes = initialBytes
        val playlistLines = playlistText.replace("\r", "").lines()
        val totalSegments = playlistLines.count { it.isNotBlank() && !it.trim().startsWith("#") }
        var completedSegments = 0
        var visitedSegmentBytes = 0L

        for (raw in playlistLines) {
            currentCoroutineContext().ensureActive()
            val line = raw.trim()
            if (line.isEmpty()) { output.append(raw).append('\n'); continue }
            if (line.startsWith("#EXT-X-KEY:", true)) {
                val method = attribute(line, "METHOD").orEmpty()
                if (!method.equals("AES-128", true) && !method.equals("NONE", true)) {
                    throw IOException("This HLS encryption method is not supported for offline downloads.")
                }
                val keyUri = attribute(line, "URI")
                if (!keyUri.isNullOrBlank()) {
                    val keyFile = File(keyDir, "key" + keyIndex + ".bin")
                    if (!keyFile.exists() || keyFile.length() == 0L) {
                        val bytes = fetchToFile(URL(URL(playlistUrl), keyUri).toString(), keyFile, headers, onConnection)
                        addedBytes += bytes
                        progressBytes += bytes
                        onProgress(progressBytes, estimatedTotalBytes)
                    }
                    output.append(line.replace(Regex("URI=\"[^\"]*\"", RegexOption.IGNORE_CASE), "URI=\"keys/" + keyFile.name + "\"")).append('\n')
                    keyIndex++
                } else output.append(raw).append('\n')
                continue
            }
            if (line.startsWith("#EXT-X-MAP:", true)) {
                val mapUri = attribute(line, "URI") ?: throw IOException("HLS initialization map is missing its URI.")
                val mapFile = File(segmentDir, "init" + mapIndex + ".bin")
                if (!mapFile.exists() || mapFile.length() == 0L) {
                    val bytes = fetchToFile(URL(URL(playlistUrl), mapUri).toString(), mapFile, headers, onConnection)
                    addedBytes += bytes
                    progressBytes += bytes
                    onProgress(progressBytes, estimatedTotalBytes)
                }
                output.append(line.replace(Regex("URI=\"[^\"]*\"", RegexOption.IGNORE_CASE), "URI=\"segments/" + mapFile.name + "\"")).append('\n')
                mapIndex++
                continue
            }
            if (line.startsWith("#")) { output.append(raw).append('\n'); continue }

            val segmentUrl = URL(URL(playlistUrl), line).toString()
            val extension = URL(segmentUrl).path.substringAfterLast('.', "").lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "bin"
            val segmentFile = File(segmentDir, "segment" + segmentIndex.toString().padStart(6, '0') + "." + extension)
            if (!segmentFile.exists() || segmentFile.length() == 0L) {
                val bytes = fetchToFile(segmentUrl, segmentFile, headers, onConnection)
                if (bytes <= 0L) throw IOException("The HLS source returned an empty media segment.")
                addedBytes += bytes
                progressBytes += bytes
            }
            visitedSegmentBytes += segmentFile.length()
            completedSegments++
            // Estimate remaining segments from the average size received so far.
            // This avoids extra network probes and works for every HLS provider.
            val dynamicTotal = if (estimatedTotalBytes > 0L) estimatedTotalBytes else if (completedSegments > 0 && totalSegments > completedSegments) {
                val averageSegmentBytes = visitedSegmentBytes / completedSegments
                (progressBytes + averageSegmentBytes * (totalSegments - completedSegments)).coerceAtLeast(progressBytes)
            } else if (completedSegments == totalSegments && progressBytes > 0L) progressBytes else -1L
            onProgress(progressBytes, dynamicTotal)
            output.append("segments/").append(segmentFile.name).append('\n')
            segmentIndex++
        }
        if (segmentIndex == 0) throw IOException("The HLS media playlist contains no downloadable video segments.")
        localPlaylist.parentFile?.mkdirs()
        localPlaylist.writeText(output.toString(), Charsets.UTF_8)
        return addedBytes
    }

    private data class PlaylistResponse(val text: String, val finalUrl: String)

    private suspend fun fetchText(url: String, headers: Map<String, String>, callback: (HttpURLConnection?) -> Unit): PlaylistResponse {
        val connection = open(url, headers)
        callback(connection)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HLS request failed (HTTP $code).")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return PlaylistResponse(text, connection.url.toString())
        } finally {
            callback(null)
            connection.disconnect()
        }
    }

    private suspend fun fetchToFile(url: String, file: File, headers: Map<String, String>, callback: (HttpURLConnection?) -> Unit): Long {
        currentCoroutineContext().ensureActive()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".part")
        val connection = open(url, headers)
        callback(connection)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HLS segment request failed (HTTP $code).")
            var count = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        count += read
                    }
                }
            }
            if (count <= 0L) throw IOException("The HLS source returned an empty media segment.")
            if (file.exists()) file.delete()
            if (!temporary.renameTo(file)) throw IOException("Could not save an HLS media segment.")
            return count
        } finally {
            callback(null)
            connection.disconnect()
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "Nexvio/1.0")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (key, value) ->
                if (!key.equals("host", true) && !key.equals("content-length", true) && !key.equals("range", true) && !key.equals("accept-encoding", true)) {
                    setRequestProperty(key, value)
                }
            }
        }
}
