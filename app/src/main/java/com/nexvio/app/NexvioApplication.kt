package com.nexvio.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/**
 * Application class. Its job is to make posters and TMDB lists survive going offline:
 * - Coil keeps every image it downloads on disk and does NOT re-validate it against the
 *   server's cache headers, so cached posters keep showing with no connection.
 * - [appContext] lets non-UI code (the TMDB fetchers) read/write the offline JSON cache.
 */
class NexvioApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        NexvioAnalytics.trackAppOpen(applicationContext)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(File(filesDir, "image_cache"))
                .maxSizeBytes(400L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()

    companion object {
        @Volatile var appContext: Context? = null
    }
}
