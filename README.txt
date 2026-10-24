NEXVIO ROTATION / PLAYBACK FIX

This is a targeted replacement for the PlayerScreen function and AndroidManifest.xml.
It is intended to stop Android from recreating MainActivity when orientation changes,
which can dispose the ExoPlayer and make you select the channel again.

1. Replace app/src/main/AndroidManifest.xml with AndroidManifest.xml from this ZIP.
2. Open app/src/main/java/com/nexvio/app/MainActivity.kt.
3. Add this import with the other imports:
   import androidx.activity.compose.BackHandler
   (The replacement currently uses the fully-qualified BackHandler name, so this import
   is optional; no need to add it.)
4. Replace ONLY the existing @Composable private fun PlayerScreen(...) function with
   PlayerScreen.kt.txt. Stop immediately before:
   private data class PlaybackFailure(...)
5. Do not replace the rest of MainActivity.kt. Keep your playlist/parser code.
6. Commit the changes and wait for GitHub Actions to build the APK.

Expected:
- Tapping Fullscreen requests landscape without intentionally recreating the Activity.
- The existing ExoPlayer is retained during orientation/layout changes.
- Exit fullscreen returns orientation to the device sensor setting.
- Android Back exits fullscreen before leaving the player.

I have not run a Gradle build against your repository, so let GitHub Actions confirm compilation.
If the build fails, send the first actual compiler error from Actions.
