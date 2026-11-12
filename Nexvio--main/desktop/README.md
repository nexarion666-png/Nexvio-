# Nexvio for Windows

This is the native Windows application module built with Kotlin and Compose Desktop. It is separate from the React website and does not run the Android APK inside an emulator.

## Build on Windows

From the `Nexvio--main` directory:

```powershell
.\gradlew.bat :desktop:run
.\gradlew.bat :desktop:packageMsi :desktop:packageExe
```

The native installers are written under `desktop/build/compose/binaries/main-release/`.

## Porting status

The first milestone establishes the native desktop application shell and Windows installer pipeline. The Android app uses Android framework APIs and Media3, so its screens and platform services cannot be reused unchanged in a Windows JVM process.

The next milestones are to extract platform-independent Kotlin logic, port the Compose screens to shared UI where possible, and replace Android-specific playback, file access, permissions, notifications, and lifecycle integrations with Windows-compatible implementations. Feature parity must be verified before release.
