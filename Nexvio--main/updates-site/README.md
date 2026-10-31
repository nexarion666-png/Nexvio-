# Nexvio public update endpoint

Deploy this directory as a separate Vercel project while keeping the Android source repository private.

## Vercel setup

1. In Vercel, import the private `nexarion666-png/Nexvio-` repository.
2. Set **Root Directory** to `updates-site`.
3. Keep the framework preset as **Other** and deploy the static files.
4. The endpoint will be `https://YOUR-VERCEL-DOMAIN.vercel.app/latest.json`.
5. Update `LATEST_UPDATE_JSON` in `NexvioUpdateChecker.kt` to that exact URL in a follow-up commit.
6. Publish the APK at the `downloadUrl` in `latest.json` and replace the placeholder URL with the real public download URL.

Do not publish an APK signed with a different key until the permanent signing setup is resolved. The JSON endpoint is public; the Android source repository remains private.
