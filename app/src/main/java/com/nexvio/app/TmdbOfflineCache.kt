package com.nexvio.app

import coil.imageLoader
import coil.request.ImageRequest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the last good TMDB list responses on disk so Home and Search still have content
 * (and poster paths for the image cache) when the phone is offline.
 */
internal object TmdbOfflineCache {
    private const val MAX_FILES = 300

    private fun dir(): File? {
        val context = NexvioApplication.appContext ?: return null
        return File(context.filesDir, "tmdb_cache").also { it.mkdirs() }
    }

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"

    fun put(key: String, body: String) {
        val folder = dir() ?: return
        runCatching {
            File(folder, fileName(key)).writeText(JSONObject().put("k", key).put("b", body).toString())
            val files = folder.listFiles().orEmpty()
            if (files.size > MAX_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES + 50).forEach { it.delete() }
        }
    }

    fun get(key: String): String? {
        val folder = dir() ?: return null
        return runCatching {
            val file = File(folder, fileName(key))
            if (file.exists()) JSONObject(file.readText()).optString("b").takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    /** Offline search: look through every saved list for titles that contain [query]. */
    fun searchOffline(mediaType: String, query: String): List<TmdbTitle> {
        val folder = dir() ?: return emptyList()
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        return folder.listFiles().orEmpty().flatMap { file ->
            runCatching {
                val saved = JSONObject(file.readText())
                val savedType = saved.getString("k").substringBefore("|")
                parseTmdbTitles(saved.getString("b"), savedType)
            }.getOrDefault(emptyList())
        }.filter { (mediaType == "multi" || it.mediaType == mediaType) && it.title.lowercase().contains(needle) }
            .distinctBy { it.mediaType + "-" + it.id }
    }

    /** Download posters in the background so they are already on disk when the signal drops. */
    fun prefetchPosters(paths: List<String>, size: String = "w500") {
        val context = NexvioApplication.appContext ?: return
        val loader = context.imageLoader
        paths.distinct().forEach { path ->
            loader.enqueue(ImageRequest.Builder(context).data("https://image.tmdb.org/t/p/$size$path").build())
        }
    }
}
