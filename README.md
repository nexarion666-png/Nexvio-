# Nexvio — Phase 2

**Movies • Series • Live TV** — an original native Android application. Phase 2 extends the existing Kotlin/Compose application; it does not import or reuse NuvioMobile source code or assets.

## Technology and identity
- Kotlin, Jetpack Compose, Material 3
- Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Gradle 8.9
- AndroidX Media3 ExoPlayer 1.5.1 for in-app playback
- Coil 2.7.0 for optional channel logos
- Application ID: `com.nexvio.app`; version `1.0.0` (version code 1)
- Minimum SDK 24 (Android 7.0); compile/target SDK 35
- JDK 17 for builds

## Phase 2 features
- Import `.m3u` / `.m3u8` files with Android's Storage Access Framework; no broad storage permission is requested.
- Import an M3U/M3U8 text playlist from a user-provided HTTP(S) URL in a background coroutine.
- Bounded playlist download/read (5 MiB), connection/read timeouts, validation and user-facing errors.
- Reusable M3U parser for `#EXTINF`, quoted `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, channel labels, and stream URLs.
- Channel list, optional logos with fallback, search, category filter, and favorites.
- Playlist source and favorite IDs are stored locally. URL sources are fetched again on launch; local document URIs are persisted when Android grants permission.
- In-app AndroidX Media3 player with buffering state, retry, player controls, error messaging and player lifecycle cleanup.
- Existing Home, Movies, Series, Library and Settings destinations remain present.
- Parser unit tests cover the requested M3U cases.

## Security and limitations
- No playlist, account, viewing history, or favorite data is uploaded to an external service.
- The app does not bypass authentication, DRM, paywalls or access restrictions. Only import playlists and streams you are authorized to access.
- The application does not globally enable cleartext traffic. On modern Android versions, arbitrary HTTP playlist/stream requests may be blocked by Android's cleartext policy; HTTPS sources are recommended. This is deliberate rather than weakening TLS/network security globally.
- Playback depends on the stream being reachable and supported by Media3 and the device. Some streams require special headers, authentication, codecs or formats not supported here.
- No movie/series catalogue, playback progress history, subtitle/audio selection, downloads, addon manager, cloud sync or accounts are implemented.
- URL playlist contents are not persisted; the source URL is re-fetched when the app launches. Local playlist access can expire if Android revokes the URI permission.
- Favorites are local and keyed to the channel identifier and stream URL.

## Build and tests
With JDK 17, Android SDK Platform 35 and Gradle 8.9 available:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The APK, if a build succeeds, is at `app/build/outputs/apk/debug/app-debug.apk`. This delivery environment had no installed Gradle executable or wrapper JAR, so a full Android build and JUnit execution were not possible here. The M3U parser source was independently compiled with `kotlinc`; this is not a substitute for an Android build or running the JUnit suite.

## GitHub Actions APK
The existing `.github/workflows/android-build.yml` workflow is retained. It runs on pushes to `main`/`master` and manual dispatch, sets up JDK 17 and Android SDK 35, provisions Gradle 8.9, generates the wrapper, runs `assembleDebug` and `testDebugUnitTest`, then uploads the debug APK as a workflow artifact. No secrets are required. GitHub Actions has not been run or verified as part of this delivery.

To download the APK after a successful workflow:
1. Open the repository's **Actions** tab.
2. Open the successful **Android build** run.
3. Download the `Nexvio-Phase-2-debug-apk` artifact from **Artifacts**.
4. Extract the artifact ZIP and install the APK on your Android device. Android may ask you to permit installation from the browser or file manager.

## Project structure
- `app/src/main/java/com/nexvio/app/MainActivity.kt` — existing app screens plus playlist/channel UI and player
- `app/src/main/java/com/nexvio/app/M3uParser.kt` — reusable M3U parser and channel model
- `app/src/test/java/com/nexvio/app/M3uParserTest.kt` — parser unit tests
- `.github/workflows/android-build.yml` — Android build/test/APK workflow
- `app/src/main/AndroidManifest.xml` — INTERNET permission and network security policy

## Uploading from a phone
1. Extract the ZIP.
2. Open your existing Nexvio repository: https://github.com/nexarion666-png/Nexvio-
3. Upload the extracted **contents** to the repository root, replacing the previous Phase 1 files when prompted. Do not add an extra enclosing folder.
4. Ensure `.github/workflows/android-build.yml` is included.
5. Commit to the primary branch and inspect the Actions run. Do not treat an APK as available unless the workflow succeeds.
