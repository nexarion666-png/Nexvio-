# Nexvio download and update site

This directory is both the public Android download landing page and the public update feed used by the Android app.

## Deploy

1. Create a static hosting project from this repository.
2. Set its root/output directory to `updates-site` (no build command is needed).
3. Deploy over HTTPS.
4. Keep `index.html` and `latest.json` together at the site root.
5. Confirm the deployed `/latest.json` is publicly readable and that the website Download button opens its APK.

The Android app currently checks `https://nexvio-updates-site-phi.vercel.app/latest.json`. If you deploy to a different domain, change `LATEST_UPDATE_JSON` in `app/src/main/java/com/nexvio/app/NexvioUpdateChecker.kt` to the new HTTPS endpoint and publish a new Android build.

## Automatic publishing

Main-branch Android builds publish the APK to the public `Nexvio-Downloads` GitHub Releases repository. After that upload succeeds, the Android GitHub Actions workflow automatically writes the matching version, version code, release URL, and release notes to `latest.json`, then commits that metadata to the source repository.

The Vercel site serves this file directly, so its Download button and the Android in-app update checker use the same latest-release feed. No manual edit to `latest.json` is needed for normal main-branch builds.

The workflow requires the repository Actions secret `NEXVIO_DOWNLOADS_TOKEN` with permission to publish releases in `nexarion666-png/Nexvio-Downloads`. It also uses the permanent Android signing keystore secrets already required by the build.

The Android app will notify users when a newer version is available. Android still requires the user to approve installation; apps cannot silently install APK updates through the ordinary installer flow.

## Current published release

The metadata in `latest.json` must always describe the actual APK available at its `downloadUrl`. Do not change the version fields until the new signed APK has been uploaded.
