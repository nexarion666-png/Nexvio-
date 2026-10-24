NEXVIO FULLSCREEN FIX v2

1. Replace app/src/main/AndroidManifest.xml with AndroidManifest.xml from this ZIP.
2. In app/src/main/java/com/nexvio/app/MainActivity.kt, add this import:
   import androidx.activity.compose.BackHandler
3. Find the existing line:
   @Composable private fun PlayerScreen(channel: TvChannel, onBack:()->Unit) {
4. Replace the entire existing PlayerScreen function only, up to but not including:
   private data class PlaybackFailure(...)
   with the contents of PlayerScreen.kt.txt in this ZIP.
5. Do not replace the whole MainActivity.kt with PlayerScreen.kt.txt. It is only the complete replacement for the PlayerScreen function.
6. Commit the changes and wait for the GitHub Actions build to finish. Install the newly built APK, not the old APK.

This keeps the playlist importer and playback diagnostic helper untouched.
