# Nexvio — Phase 1

**Movies • Series • Live TV** — an original native Android application foundation. This project was created independently; NuvioMobile was used only as a high-level capability reference. No NuvioMobile source code or assets are included.

## Stack
- Kotlin, Jetpack Compose, Material 3
- Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21
- AndroidX Activity, Navigation Compose, DataStore dependency for future local settings
- Application ID: `com.nexvio.app`
- Version: `1.0.0` (version code `1`)
- Minimum SDK: 24 (Android 7.0); target/compile SDK: 35

## Included in Phase 1
- Dark Nexvio branding and original vector launcher mark
- Home, Movies, Series, Live TV, Library, and Settings destinations
- Navigation bar, search entry point, featured layout, category cards, and honest empty states
- Explanatory feature pages for provider, playlist, playback, download, and appearance settings
- No account, API key, backend, or network connection required for the initial interface
- GitHub Actions debug-build workflow

## Not implemented yet
Real provider/addon integrations, real movie/series metadata, IPTV playlist parsing, channels, video playback, favorites persistence, watch history, playback progress, downloads, subtitle/audio selection, and configurable persisted settings. The catalogue cards and sections are layout foundations only. No working streams or fabricated playback results are supplied.

## Build
Requires JDK 17 and Android SDK Platform 35. With a working Gradle wrapper, run:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The expected APK output is `app/build/outputs/apk/debug/app-debug.apk`. The delivered archive does **not** include `gradle-wrapper.jar` because the build environment could not reach the upstream host to retrieve it. For a local build, install Gradle 8.9 and run `gradle assembleDebug` / `gradle testDebugUnitTest`. The GitHub Actions workflow provisions Gradle 8.9 and generates the standard wrapper before building, so the workflow does not depend on a prebundled wrapper JAR.

## GitHub Actions APK
`.github/workflows/android-build.yml` runs on pushes to `main` or `master` and manual dispatch. It installs JDK 17 and Android SDK 35, runs `assembleDebug` and unit tests, then uploads `app-debug.apk` as the `Nexvio-Phase-1-debug-apk` workflow artifact. After a successful workflow: open the repository's **Actions** tab, select the successful Android build run, and download the artifact from **Artifacts**. No secrets are required. GitHub execution has not been run or verified as part of this delivery.

## Project layout
- `app/src/main/java/com/nexvio/app/` — app entry point and Compose screens
- `app/src/main/res/` — app label, theme, original vector brand mark
- `app/src/test/` — basic JVM unit test
- `.github/workflows/` — CI build and artifact upload
- `gradle/` — wrapper distribution configuration

## Upload to your existing GitHub repository (phone-friendly)
1. Download and extract `Nexvio-Phase-1.zip` on your phone.
2. Open `https://github.com/nexarion666-png/Nexvio-` in your browser and confirm you are signed in.
3. Choose **Add file → Upload files**. Upload the extracted project contents directly into the repository root, including hidden `.github` workflow files if your upload method supports them. Do not upload an extra enclosing `Nexvio-Phase-1/` folder.
4. Commit the upload to the primary branch. If GitHub's mobile browser cannot upload the hidden `.github` directory or many files at once, use GitHub's web desktop mode or a computer so the workflow is included.
5. Open **Actions** and inspect the build result. Do not treat the APK as available until a workflow run succeeds.

## Verification status
This archive was checked for ZIP integrity and required source/configuration files. Android compilation, APK generation, emulator testing, and GitHub Actions execution were not performed in the available environment. Therefore no APK is included. See the delivery report for unresolved items.
