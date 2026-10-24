Nexvio fullscreen patch
=======================

This ZIP contains:
- AndroidManifest.xml: replacement manifest with orientation/screen-size configChanges.
- MainActivity.kt.patch: a targeted unified diff for the current MainActivity.kt you pasted.

Important: the MainActivity patch is a diff, not a complete replacement file. Apply it to:
app/src/main/java/com/nexvio/app/MainActivity.kt

The change adds:
- Manual Fullscreen button that requests sensor landscape.
- Automatic fullscreen presentation when the device is in landscape.
- PlayerView fills the available area in fullscreen.
- System bars are hidden in fullscreen and restored on exit.
- Back button exits manually requested fullscreen.
- Existing stream/player logic is left unchanged.

If applying the diff through GitHub's phone editor is difficult, tell me and I can give you exact replacement sections one at a time.
