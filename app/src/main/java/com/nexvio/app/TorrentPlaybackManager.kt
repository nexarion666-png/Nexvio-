package com.nexvio.app

import android.content.Context
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Torrent-backed loopback HTTP byte-range source for Media3. */
internal object TorrentPlaybackManager {
    private val lock = Any()
    @Volatile private var engine: SessionManager? = null
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var info: TorrentInfo? = null
    @Volatile private var selectedFile: File? = null
    @Volatile private var selectedIndex = -1
    @Volatile private var listener: ServerSocket? = null

    private fun engine(context: Context): SessionManager = synchronized(lock) {
        engine ?: SessionManager().also { it.start(); engine = it }
    }

    suspend fun prepare(context: Context, infoHash: String, preferredIndex: Int?, onStatus: (String) -> Unit): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(infoHash.matches(Regex("(?i)[0-9a-f]{40}"))) { "Invalid torrent info hash." }
            val session = engine(context.applicationContext)
            val hash = infoHash.lowercase()
            var found = session.find(Sha1Hash(hash))
            if (found == null || !found.isValid) {
                onStatus("Adding torrent and discovering peers…")
                session.download("magnet:?xt=urn:btih:" + hash, File(context.filesDir, "torrent-streams/" + hash), org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
            val deadline = System.currentTimeMillis() + 90000L
            var torrentInfo: TorrentInfo? = null
            while (System.currentTimeMillis() < deadline) {
                found = session.find(Sha1Hash(hash))
                if (found != null && found.isValid) {
                    torrentInfo = runCatching { found.torrentFile() }.getOrNull()
                    if (torrentInfo != null && torrentInfo.isValid) break
                }
                onStatus("Discovering peers and reading torrent metadata…")
                Thread.sleep(500)
            }
            val torrent = found ?: throw IllegalStateException("No torrent handle. Try a source with active peers.")
            val metadata = torrentInfo ?: throw IllegalStateException("Torrent metadata timed out. Try another source.")
            val files = metadata.files()
            val count = files.numFiles()
            require(count > 0) { "No files found in this torrent." }
            val extensions = listOf(".mkv", ".mp4", ".m4v", ".avi", ".webm", ".mov", ".ts", ".mpeg", ".mpg")
            val videoIndices = (0 until count).filter { index ->
                val name = files.filePath(index).toString().lowercase()
                extensions.any { name.endsWith(it) } && files.fileSize(index) > 2L * 1024 * 1024
            }
            val index = preferredIndex?.takeIf { it in 0 until count && files.fileSize(it) > 0L }
                ?: videoIndices.maxByOrNull { files.fileSize(it) }
                ?: (0 until count).maxByOrNull { files.fileSize(it) }
                ?: throw IllegalStateException("No playable file in this torrent.")
            val path = files.filePath(index).toString()
            val root = File(context.filesDir, "torrent-streams/" + hash)
            root.mkdirs()
            val mediaFile = File(root, path)
            mediaFile.parentFile?.mkdirs()
            for (i in 0 until count) runCatching {
                torrent.filePriority(i, if (i == index) Priority.TOP_PRIORITY else Priority.IGNORE)
            }
            torrent.resume()
            synchronized(lock) {
                handle = torrent
                info = metadata
                selectedFile = mediaFile
                selectedIndex = index
                if (listener == null || listener!!.isClosed) {
                    val socket = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
                    listener = socket
                    thread(name = "NexvioTorrentHttp", isDaemon = true) {
                        while (!socket.isClosed) {
                            try {
                                val client = socket.accept()
                                thread(name = "NexvioTorrentClient", isDaemon = true) { serve(client) }
                            } catch (_: Exception) { if (!socket.isClosed) { /* retry accept */ } }
                        }
                    }
                }
                "http://127.0.0.1:" + listener!!.localPort + "/" + index
            }
        }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 150000
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine() ?: return
            val requestedPath = request.split(" ").getOrNull(1).orEmpty().substringBefore("?").trim('/')
            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(":").trim()
            }
            val index = requestedPath.toIntOrNull() ?: selectedIndex
            val torrent = handle ?: return
            val metadata = info ?: return
            val file = selectedFile ?: return
            if (index != selectedIndex || !torrent.isValid) {
                client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            val storage = metadata.files()
            val length = storage.fileSize(index)
            if (length <= 0L) return
            val pair = parseRange(rangeHeader, length)
            val start = pair.first
            val end = pair.second
            val pieceSize = metadata.pieceLength().toLong().coerceAtLeast(1L)
            val absoluteStart = storage.fileOffset(index) + start
            val absoluteEnd = storage.fileOffset(index) + end
            val firstPiece = (absoluteStart / pieceSize).toInt()
            val lastPiece = (absoluteEnd / pieceSize).toInt()
            val output = client.getOutputStream()
            for (piece in firstPiece..lastPiece) if (!torrent.havePiece(piece)) {
                runCatching { torrent.setPieceDeadline(piece, (piece - firstPiece) * 100) }
            }
            val until = System.currentTimeMillis() + 120000L
            while (System.currentTimeMillis() < until && torrent.isValid) {
                var ready = true
                for (piece in firstPiece..lastPiece) if (!torrent.havePiece(piece)) { ready = false; break }
                if (ready) break
                Thread.sleep(150)
            }
            for (piece in firstPiece..lastPiece) if (!torrent.havePiece(piece)) {
                runCatching { output.write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
                return
            }
            val response = if (rangeHeader == null) "200 OK" else "206 Partial Content"
            val headers = StringBuilder()
                .append("HTTP/1.1 ").append(response).append("\r\n")
                .append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(end - start + 1).append("\r\n")
                .also { if (rangeHeader != null) it.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(length).append("\r\n") }
                .append("Connection: close\r\n\r\n").toString()
            output.write(headers.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                var remaining = end - start + 1
                val buffer = ByteArray(65536)
                while (remaining > 0) {
                    val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    remaining -= n
                }
                output.flush()
            }
        }
    }

    private fun parseRange(header: String?, length: Long): Pair<Long, Long> {
        if (header.isNullOrBlank() || !header.startsWith("bytes=")) return 0L to (length - 1L)
        val range = header.removePrefix("bytes=").substringBefore(",")
        val start = range.substringBefore("-").toLongOrNull() ?: 0L
        val end = range.substringAfter("-", "").toLongOrNull()?.coerceAtMost(length - 1L) ?: (length - 1L)
        require(start in 0 until length && end >= start) { "Invalid media byte range." }
        return start to end
    }
}
