# Nexvio

# Nexvio

**Movies • Series • Live TV** — a native Android app (Kotlin, Jetpack Compose, Material 3).

Nexvio is built phone-first: code lives on GitHub, GitHub Actions builds the APK, and the website/update feed deploy from the same repository.

## Features

### Home
- Full-screen **featured hero** that runs edge to edge under the status bar, shows a bright poster, title, year/rating and a **View Details** button, rotates every 5 seconds and can be swiped. Hero size follows the Home style in Layout settings (Featured / Compact / Grid).
- Trending Movies and Popular Series rows, custom collections, Continue watching and Recently watched.

### Search and details
- TMDB-powered catalogue and search for movies and series, with season/episode pickers for series.
- Stream discovery through compatible add-ons/plugins and content providers (see `docs/`).
- Watchlist (Library tab) — tap an item to open its details page.

### Playback
- AndroidX Media3 ExoPlayer with buffering state, retry, error messages, fullscreen/rotation handling and subtitle support.
- **Resume**: playback resumes where you stopped. Episodes you have almost finished (over 92%) restart from the beginning instead.
- **TV show folders**: downloaded episodes are grouped into one folder per show (anything named like `S01E02` is detected as an episode), sorted by season and episode, with the show's own TMDB poster as the folder cover.
- **Next / Previous** buttons in the player while watching a show folder, with an `Episode x/y` counter in the title.
- **Auto-play next episode**: when an episode ends it is marked as watched and the next episode in the folder starts. Turn it off in Settings → Playback.

### Downloads and Library
- Download direct media files for offline viewing (HLS/DASH playlists cannot be saved yet).
- Library holds the watchlist and a Downloads view with Movies and TV show folders.

### Offline behaviour
- **Posters are cached on disk** (up to 400 MB, not re-validated against server headers), so every poster you have already seen — and the ones Nexvio prefetches for Home, Search and the watchlist — still shows with no connection.
- **TMDB lists are cached**: Home, Search and the series season/episode lists fall back to the last saved copy when the network is down. Offline search looks through the lists saved on the device.
- Open the app online at least once to fill the cache. A title that was never loaded online cannot be shown offline.

### Live TV
- Import `.m3u` / `.m3u8` through Android's Storage Access Framework or from an HTTP(S) URL, with a bounded download (5 MiB), timeouts and clear errors.
- Reusable M3U parser (`#EXTINF`, `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`), channel search, category filter and favorites.

### Profiles, accounts and settings
- Multiple profiles with their own watchlist, history and downloads.
- Optional account and cloud sync (server side lives in `web/` and `supabase/migrations`).
- Settings for layout, playback, integrations, notifications and account; in-app update checker fed by `updates-site/latest.json`.

## Technology
- Kotlin 2.0.21, Jetpack Compose, Material 3, Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17
- AndroidX Media3 ExoPlayer 1.5.1
- Coil 2.7.0 for images (custom disk cache in `NexvioApplication.kt`)
- Application ID `com.nexvio.app`; min SDK 24 (Android 7.0), compile/target SDK 35
- Version name/code are set in `app/build.gradle.kts` (overridable with `NEXVIO_VERSION_NAME` / `NEXVIO_VERSION_CODE`)

## Project structure
- `app/src/main/java/com/nexvio/app/MainActivity.kt` — navigation, Home, player and most screens
- `NexvioApplication.kt` — application class and the offline image cache configuration
- `TmdbCatalogueScreen.kt` — TMDB catalogue, search, details, watchlist store
- `TmdbOfflineCache.kt` — saved TMDB responses and poster prefetching for offline use
- `DownloadsScreen.kt` — downloads, TV show folders, Library screen
- `PlaybackSettingsScreen.kt`, `LayoutSettingsScreen.kt`, `AccountSettingsScreen.kt` — settings
- `M3uParser.kt` — M3U parser and channel model
- `ProfileSystem.kt`, `WatchHistory.kt`, `NexvioCloudSync.kt`, `NexvioUpdateChecker.kt` — profiles, history, sync, updates
- `Nuvio*.kt`, `PluginFoundation.kt`, `PluginRepositoryScreen.kt`, `AddonManagerScreen.kt`, `ContentProvidersScreen.kt` — plugin/add-on support
- `app/src/test/java/com/nexvio/app/` — unit tests
- `web/` — website and API (Vite, deployed on Vercel); `updates-site/` — update feed; `supabase/` — database migrations
- `.github/workflows/` — Android build/release and web build workflows

## Build and test
With JDK 17, Android SDK Platform 35 and Gradle 8.9:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions APK
`.github/workflows/android-build.yml` runs on pushes and pull requests to `main`/`master`, on `v*` tags and on manual dispatch. It checks the bundled JavaScript, sets up JDK 17 and Android SDK 35, builds the debug APK, runs the unit tests and uploads the APK as a workflow artifact.

To download the APK:
1. Open the repository's **Actions** tab and open the latest successful run.
2. Download the APK from **Artifacts**.
3. Extract the ZIP and install the APK. Android may ask you to allow installs from your browser or file manager.

## Uploading changes from a phone
1. Extract the ZIP.
2. Upload the extracted **contents** to the repository root and replace files when asked. Do not add an extra enclosing folder.
3. Commit to the main branch and check the Actions run.

## TMDB API key
The TMDB key is currently written directly in `TmdbCatalogueScreen.kt` and `DownloadsScreen.kt`. Because the repository is public, consider replacing it with your own key and rotating the old one on themoviedb.org.

## Security and limitations
- Nexvio does not bypass authentication, DRM, paywalls or access restrictions. Only import playlists and streams you are authorized to access.
- Cleartext HTTP is not enabled globally; HTTPS sources are recommended.
- Playback depends on the stream being reachable and supported by Media3 and your device.
- Downloads need a direct media URL (MP4/M4V/MKV/WebM/MP3/AAC). Streaming playlists (HLS/DASH/M3U) cannot be saved offline yet.
- Episodes streamed online (not downloaded) do not yet have Next/Previous or auto-next; those work for downloaded show folders.
- Offline mode shows only content that was loaded online before.

## Third-party notices
See `THIRD_PARTY_NOTICES.md`.

## In-app updater verification

This test-only marker prepares a fresh build to verify the in-app update banner, APK download progress, and Android installer handoff after the installation-button fix. It does not change app functionality.
