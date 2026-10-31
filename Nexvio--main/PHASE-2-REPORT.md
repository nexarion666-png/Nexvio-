# Nexvio Phase 2 — Implementation Report

## Scope and preservation
- Inspected the uploaded Phase 1 archive before editing.
- Preserved application ID `com.nexvio.app`, version `1.0.0` / code `1`, Kotlin + Compose + Material 3 architecture, SDK levels, six main destinations, root Gradle configuration, and existing GitHub Actions build steps.
- No NuvioMobile source code or assets were imported.

## Files created
- `app/src/main/java/com/nexvio/app/M3uParser.kt` — channel model and reusable M3U parser.
- `app/src/test/java/com/nexvio/app/M3uParserTest.kt` — ten parser unit tests.

## Files modified
- `app/src/main/java/com/nexvio/app/MainActivity.kt` — local/URL playlist import, bounded background download/read, channel list, search, category filtering, favorites, source persistence, player screen, and user-facing error/loading states while retaining existing destinations.
- `app/build.gradle.kts` — Media3 ExoPlayer/UI, Coil, and lifecycle-compose dependencies.
- `app/src/main/AndroidManifest.xml` — INTERNET permission; cleartext traffic remains disabled.
- `README.md` — Phase 2 documentation, build instructions and limitations.
- `.github/workflows/android-build.yml` — preserved existing SDK/Gradle setup; artifact label updated to Phase 2.

## Dependencies added
- `androidx.media3:media3-exoplayer:1.5.1`
- `androidx.media3:media3-ui:1.5.1`
- `androidx.lifecycle:lifecycle-runtime-compose:2.8.7`
- `io.coil-kt:coil-compose:2.7.0`

Existing JUnit 4.13.2 dependency is used for parser tests.

## Permissions and network security
- Added `android.permission.INTERNET`.
- No broad storage, contacts, location, microphone or camera permissions.
- Cleartext traffic is not globally enabled. HTTPS is recommended; arbitrary HTTP requests may be blocked by Android's cleartext policy.
- Playlist downloads are capped at 5 MiB and use 12-second connection and 15-second read timeouts. URL fetches and file reads run on IO dispatchers. Playlist contents and URLs are not logged.

## Tests actually run
- `kotlinc app/src/main/java/com/nexvio/app/M3uParser.kt -d /tmp/nexvio-parser.jar` — **passed** with one non-fatal warning about an unnecessary non-null assertion.
- A standalone Kotlin parser smoke test — **passed** for multiple entries, quoted attributes containing commas, CRLF, query-string preservation, and missing stream URLs.
- ZIP integrity and required-file presence — checked during packaging.
- `./gradlew assembleDebug` — **not run**: Gradle is not installed in the environment and `gradle-wrapper.jar` is absent.
- `./gradlew testDebugUnitTest` — **not run** for the same reason. The ten JUnit tests are included but not claimed as passed.
- Emulator/physical-device playback and navigation tests — **not run**.
- GitHub Actions — not run or verified in this delivery.

## Build status
**Source updated; Android build unverified. No APK is included.** The existing GitHub Actions workflow provisions JDK 17, Android SDK 35, Gradle 8.9 and generates the wrapper before running `assembleDebug testDebugUnitTest`. A successful workflow uploads `app/build/outputs/apk/debug/app-debug.apk` as `Nexvio-Phase-2-debug-apk`.

## Known issues / limitations
- Android build and full Kotlin/Compose compilation have not been verified in this environment.
- Cleartext HTTP may be blocked by Android's network security policy; HTTPS is recommended. This avoids globally weakening network security.
- Media3 cannot guarantee every IPTV stream, codec, server or authentication configuration will work.
- Local playlist re-open depends on persisted document URI permission remaining available.
- URL playlist source is stored, not playlist contents; it is re-fetched when the app starts.
- Favorites persist locally; playback history/progress, movie/series catalogues, downloads, and addon integrations are out of scope.

## Build/install using GitHub Actions
1. Upload the extracted project contents directly to the root of the existing Nexvio repository.
2. Commit to `main` (or `master`).
3. Open **Actions** and select the **Android build** run.
4. Wait for the build and unit-test job to succeed.
5. Download the `Nexvio-Phase-2-debug-apk` artifact, extract it, and install the APK on the phone.
6. Test file import, HTTPS playlist import, search, category filter, favorites after restart, playback, retry, and navigation on-device before considering Phase 2 accepted.
