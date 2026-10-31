# Nexvio download and update site

This directory is both the public Android download landing page and the public update feed used by the Android app.

## Deploy

1. Create a static hosting project from this repository.
2. Set its root/output directory to `updates-site` (no build command is needed).
3. Deploy over HTTPS.
4. Keep `index.html` and `latest.json` together at the site root.
5. Confirm the deployed `/latest.json` is publicly readable and that the website Download button opens its APK.

The Android app currently checks `https://nexvio-updates-site.vercel.app/latest.json`. If you deploy to a different domain, change `LATEST_UPDATE_JSON` in `app/src/main/java/com/nexvio/app/NexvioUpdateChecker.kt` to the new HTTPS endpoint and publish a new Android build.

## Publishing a release

1. Build a release APK with the same permanent Android signing keystore used for every previous Nexvio release.
2. Upload the APK to a public HTTPS download location (the existing Nexvio-Downloads GitHub Releases repository can host it).
3. Update `latest.json` with the new `version`, strictly increasing `versionCode`, public `downloadUrl`, and human-readable `releaseNotes`.
4. Deploy the updated static site.
5. Install the new APK on a test device over the previous Nexvio installation before announcing it.

Example metadata:

```json
{
  "version": "1.0.2",
  "versionCode": 3,
  "downloadUrl": "https://example.com/Nexvio.apk",
  "releaseNotes": "Bug fixes and performance improvements."
}
```

Never publish a download URL for an APK until that exact file is uploaded and publicly accessible. Android requires updates to be signed with the same signing certificate as the installed app. The app opens the download URL and Android asks the user to approve installation; ordinary Android apps cannot silently install themselves.

## Current published release

The metadata in `latest.json` must always describe the actual APK available at its `downloadUrl`. Do not change the version fields until the new signed APK has been uploaded.
