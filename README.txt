Nexvio rotation/playback preservation fix

Replace these files in the existing repository (keep these exact paths):
1. app/src/main/AndroidManifest.xml
2. app/src/main/java/com/nexvio/app/MainActivity.kt

Root cause found:
The top-level AndroidManifest.xml already mentioned configChanges, but Android Gradle builds use app/src/main/AndroidManifest.xml. The actual app manifest did not declare orientation/screen-size changes, so Android recreated MainActivity on rotation. The selected channel lived in Compose remember state, and PlayerScreen owned ExoPlayer in a DisposableEffect; recreation therefore lost the selection and released the active player.

Fix summary:
- Declare orientation/screen-size configuration changes in the manifest actually used by the app, allowing Compose to recompose without destroying the Activity for ordinary rotation.
- Save the selected channel in rememberSaveable for restoration if the Activity is recreated for another reason.
- Keep the player effect keyed only to stream URL/retry, reattach the existing player through AndroidView updates, detach PlayerView cleanly, use fit resize mode, and size the video to the available landscape area.
- Automatically enter fullscreen UI in landscape, restore portrait layout in portrait, provide a manual fullscreen toggle, hide system bars in fullscreen, and let Android Back exit fullscreen first.
- Preserve whether playback should resume after a real lifecycle pause; rotation itself should not reset the media item or call prepare again.

Validation:
This ZIP has not been built with Gradle in this environment. Please run the repository's Android build and test workflow before installing the APK.
