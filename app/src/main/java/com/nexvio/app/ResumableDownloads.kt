package com.nexvio.app

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import coil.imageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val DOWNLOAD_CHANNEL_ID = "nexvio_downloads"

/** Server answered, but not with a downloadable media file. Not resumable, so the row shows "Failed". */
private class DownloadHttpException(message: String) : Exception(message)

/**
 * Pausable / resumable downloader.
 *
 * Android's DownloadManager has no public pause or resume, so new downloads run here instead:
 * plain HTTP with Range requests, writing to "<name>.part" and renaming to "<name>" when complete.
 * Pausing just stops the transfer; resuming continues from the size of the .part file.
 *
 * Downloads created by the old DownloadManager code keep working. They are told apart by id:
 * DownloadManager ids are small counters, ours are millisecond timestamps (see [isOwn]).
 */
internal object ResumableDownloads {
    private const val OWN_ID_FLOOR = 1_000_000_000_000L
    fun isOwn(id: Long): Boolean = id >= OWN_ID_FLOOR

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()
    private val reconciledProfiles = mutableSetOf<String>()

    /** Transfers that are actually running right now (paused ones do not count). */
    fun activeCount(): Int = jobs.values.count { it.isActive }

    fun prefs(context: Context, profileId: String): SharedPreferences =
        context.getSharedPreferences(if (profileId == "primary") "nexvio_downloads" else "nexvio_downloads_$profileId", Context.MODE_PRIVATE)

    /** Same folder the DownloadManager version used, so old and new files live side by side. */
    fun directory(context: Context, profileId: String): File {
        val base = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")).resolve("Nexvio")
        return if (profileId == "primary") base else base.resolve(profileId)
    }

    fun record(prefs: SharedPreferences, id: Long): JSONObject? =
        prefs.getString("own_$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    @Synchronized
    private fun update(prefs: SharedPreferences, id: Long, block: (JSONObject) -> Unit) {
        val rec = record(prefs, id) ?: return
        block(rec)
        prefs.edit().putString("own_$id", rec.toString()).apply()
    }

    @Synchronized
    fun enqueue(context: Context, profileId: String, url: String, title: String): Long {
        val app = context.applicationContext
        val prefs = prefs(app, profileId)
        var id = System.currentTimeMillis().coerceAtLeast(OWN_ID_FLOOR)
        while (prefs.contains("own_$id")) id++
        val dir = directory(app, profileId).also { it.mkdirs() }
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Nexvio media" }
        var name = safeTitle
        var counter = 1
        while (File(dir, name).exists() || File(dir, "$name.part").exists()) { counter++; name = "$safeTitle ($counter)" }
        runCatching { File(dir, "$name.part").createNewFile() }
        val rec = JSONObject()
            .put("url", url)
            .put("title", title)
            .put("file", name)
            .put("status", DownloadManager.STATUS_PENDING)
            .put("total", -1L)
        prefs.edit().putString("own_$id", rec.toString()).apply()
        startJob(app, profileId, id)
        return id
    }

    fun resume(context: Context, profileId: String, id: Long) = startJob(context.applicationContext, profileId, id)

    @Synchronized
    private fun startJob(app: Context, profileId: String, id: Long) {
        val previous = jobs[id]
        if (previous != null && previous.isActive) return
        update(prefs(app, profileId), id) { it.put("status", DownloadManager.STATUS_PENDING); it.remove("error") }
        val job = scope.launch(start = CoroutineStart.LAZY) { runDownload(app, profileId, id, previous) }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
        job.start()
        DownloadService.ensureRunning(app)
    }

    fun pause(context: Context, profileId: String, id: Long) {
        update(prefs(context.applicationContext, profileId), id) { rec ->
            val status = rec.optInt("status")
            if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING) {
                rec.put("status", DownloadManager.STATUS_PAUSED)
                rec.remove("error")
            }
        }
        stopTransfer(id)
    }

    /** Stops the transfer and removes the record plus both the finished file and the .part file. */
    fun delete(context: Context, profileId: String, id: Long) {
        val app = context.applicationContext
        val prefs = prefs(app, profileId)
        val rec = record(prefs, id)
        prefs.edit().remove("own_$id").apply()
        val job = jobs[id]
        job?.cancel()
        scope.launch {
            connections[id]?.let { runCatching { it.disconnect() } }
            job?.join()
            if (rec != null) {
                val dir = directory(app, profileId)
                val name = rec.optString("file")
                if (name.isNotBlank()) {
                    runCatching { File(dir, name).delete() }
                    runCatching { File(dir, "$name.part").delete() }
                }
            }
        }
    }

    private fun stopTransfer(id: Long) {
        jobs[id]?.cancel()
        // Closing the socket is what actually interrupts a blocked read; do it off the main thread.
        scope.launch { connections[id]?.let { runCatching { it.disconnect() } } }
    }

    /**
     * A process that died mid-download leaves "running" records behind. The first time a profile's
     * list is read in a new process, turn those into "paused" so the user can resume them.
     */
    @Synchronized
    fun reconcile(prefs: SharedPreferences, profileId: String, ids: List<Long>) {
        if (!reconciledProfiles.add(profileId)) return
        ids.filter { isOwn(it) }.forEach { id ->
            if (jobs[id]?.isActive != true) {
                update(prefs, id) { rec ->
                    val status = rec.optInt("status")
                    if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING) {
                        rec.put("status", DownloadManager.STATUS_PAUSED)
                    }
                }
            }
        }
    }

    fun row(prefs: SharedPreferences, id: Long): Triple<Long, String, Int>? =
        record(prefs, id)?.let { Triple(id, it.optString("title", "Media").ifBlank { "Media" }, it.optInt("status", DownloadManager.STATUS_PENDING)) }

    fun progress(context: Context, profileId: String, id: Long): Pair<Long, Long>? {
        val rec = record(prefs(context, profileId), id) ?: return null
        val dir = directory(context, profileId)
        val name = rec.optString("file")
        val downloaded = if (rec.optInt("status") == DownloadManager.STATUS_SUCCESSFUL) File(dir, name).length() else File(dir, "$name.part").length()
        return downloaded to rec.optLong("total", -1L)
    }

    fun completedFile(context: Context, profileId: String, id: Long): File? {
        val rec = record(prefs(context, profileId), id) ?: return null
        if (rec.optInt("status") != DownloadManager.STATUS_SUCCESSFUL) return null
        val file = File(directory(context, profileId), rec.optString("file"))
        return file.takeIf { it.isFile && it.canRead() }
    }

    private suspend fun runDownload(app: Context, profileId: String, id: Long, previous: Job?) {
        previous?.join()
        val prefs = prefs(app, profileId)
        val rec = record(prefs, id) ?: return
        val dir = directory(app, profileId).also { it.mkdirs() }
        val name = rec.optString("file")
        val part = File(dir, "$name.part")
        val target = File(dir, name)
        try {
            currentCoroutineContext().ensureActive()
            update(prefs, id) { if (it.optInt("status") == DownloadManager.STATUS_PENDING) it.put("status", DownloadManager.STATUS_RUNNING) }
            transfer(rec.optString("url"), part, prefs, id)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not save the finished file.")
            update(prefs, id) { it.put("status", DownloadManager.STATUS_SUCCESSFUL) }
            notifyDownloadComplete(app, rec.optString("title"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadHttpException) {
            update(prefs, id) { it.put("status", DownloadManager.STATUS_FAILED).put("error", e.message ?: "Download failed.") }
        } catch (e: IOException) {
            // Network dropped or the socket was closed. A real pause/delete cancels us, so rethrow that case.
            currentCoroutineContext().ensureActive()
            update(prefs, id) { it.put("status", DownloadManager.STATUS_PAUSED).put("error", "Connection lost. Tap Resume to continue.") }
        } catch (e: Exception) {
            update(prefs, id) { it.put("status", DownloadManager.STATUS_FAILED).put("error", e.message ?: "Download failed.") }
        }
    }

    private suspend fun transfer(url: String, part: File, prefs: SharedPreferences, id: Long) {
        val existing = if (part.exists()) part.length() else 0L
        var conn = openFollowingRedirects(url, existing, 0)
        var profile = 0
        // Some servers reject unfamiliar headers (e.g. HTTP 428/403). Retry with other header sets before giving up.
        while (conn.responseCode in RETRY_WITH_OTHER_HEADERS && profile < 2) {
            conn.disconnect()
            profile++
            conn = openFollowingRedirects(url, existing, profile)
        }
        connections[id] = conn
        try {
            currentCoroutineContext().ensureActive()
            val code = conn.responseCode
            if (code == 416 && existing > 0) return // We already have every byte.
            if (code != 200 && code != 206) throw DownloadHttpException("The server refused the download (HTTP $code).")
            if (conn.contentType?.startsWith("text/", ignoreCase = true) == true) {
                throw DownloadHttpException("The server returned a web page, not a media file.")
            }
            // 206 = server honoured our Range header; 200 = it ignored it, so start over from byte 0.
            val resumed = code == 206 && existing > 0
            val total = if (resumed) {
                conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: conn.contentLengthLong.let { if (it > 0) it + existing else -1L }
            } else {
                conn.contentLengthLong.let { if (it > 0) it else -1L }
            }
            update(prefs, id) { it.put("total", total) }
            FileOutputStream(part, resumed).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (total > 0 && part.length() < total) throw IOException("Incomplete download")
        } finally {
            connections.remove(id, conn)
            runCatching { conn.disconnect() }
        }
    }

    private val RETRY_WITH_OTHER_HEADERS = setOf(400, 401, 403, 405, 406, 415, 428, 429)
    private const val DM_USER_AGENT = "AndroidDownloadManager/14 (Linux; U; Android 14; Nexvio)"
    private const val CHROME_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * [profile] 0 = same headers Android's DownloadManager sends (what worked before),
     * 1 = Chrome user agent + Referer, 2 = Chrome + an explicit Range header (some servers insist on one).
     */
    private fun openFollowingRedirects(startUrl: String, from: Long, profile: Int): HttpURLConnection {
        var current = startUrl
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", if (profile == 0) DM_USER_AGENT else CHROME_USER_AGENT)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (profile >= 1) {
                conn.setRequestProperty("Accept", "*/*")
                runCatching { URL(current).let { "${it.protocol}://${it.host}/" } }.getOrNull()?.let { conn.setRequestProperty("Referer", it) }
            }
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            else if (profile >= 2) conn.setRequestProperty("Range", "bytes=0-")
            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw DownloadHttpException("The server sent a bad redirect.")
                current = URL(URL(current), location).toString()
            } else {
                return conn
            }
        }
        throw DownloadHttpException("Too many redirects.")
    }
}

private fun launchIntent(context: Context): PendingIntent? =
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

internal fun notifyDownloadComplete(context: Context, title: String) {
    runCatching {
        DownloadService.ensureChannel(context)
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(10_000 + (System.currentTimeMillis() % 100_000).toInt(), notification)
    }
}

/**
 * Keeps the process alive (and shows one ongoing notification) while downloads are running,
 * so they carry on when Nexvio is in the background. It stops itself when nothing is running.
 */
class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = buildNotification(ResumableDownloads.activeCount().coerceAtLeast(1))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            loop = serviceScope.launch {
                while (true) {
                    delay(1500)
                    val count = ResumableDownloads.activeCount()
                    if (count == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(count))
                }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 caps "data sync" foreground services; stop cleanly when the system says time is up.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int) = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Nexvio downloads")
        .setContentText(if (count == 1) "Downloading 1 file" else "Downloading $count files")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .setContentIntent(launchIntent(this))
        .build()

    companion object {
        private const val NOTIFICATION_ID = 7410

        fun ensureRunning(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.getNotificationChannel(DOWNLOAD_CHANNEL_ID) == null) {
                    manager.createNotificationChannel(NotificationChannel(DOWNLOAD_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
                }
            }
        }
    }
}

/**
 * Poster images for the Downloads screen, saved as real files in the app's private storage.
 * Unlike an in-memory lookup or an image-cache entry, these survive restarts and cache clean-ups,
 * so folders keep their poster with no connection.
 */
internal object DownloadPosterStore {
    private fun dir(context: Context): File = File(context.filesDir, "download_posters").also { it.mkdirs() }

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    fun local(context: Context, key: String): File? =
        File(dir(context), fileName(key)).takeIf { it.isFile && it.length() > 0 }

    fun delete(context: Context, key: String) {
        runCatching { File(dir(context), fileName(key)).delete() }
    }

    /**
     * Saves the poster for [key]. First tries Coil's disk cache (works offline if the poster was ever
     * shown in the app), then downloads it. Returns the saved file, or null if neither worked.
     */
    suspend fun fetchAndSave(context: Context, key: String, url: String): File? = withContext(Dispatchers.IO) {
        local(context, key)?.let { return@withContext it }
        val target = File(dir(context), fileName(key))
        val temp = File(dir(context), fileName(key) + ".tmp")
        try {
            val cached = runCatching {
                context.imageLoader.diskCache?.openSnapshot(url)?.use { snapshot ->
                    snapshot.data.toFile().copyTo(temp, overwrite = true)
                }
            }.getOrNull()
            if (cached == null || temp.length() == 0L) {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 10_000
                    if (conn.responseCode !in 200..299) return@withContext null
                    conn.inputStream.use { input -> temp.outputStream().use { out -> input.copyTo(out) } }
                } finally {
                    conn.disconnect()
                }
            }
            if (temp.length() > 0 && temp.renameTo(target)) target else null
        } catch (_: Exception) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
