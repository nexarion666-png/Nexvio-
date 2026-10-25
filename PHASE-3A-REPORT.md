# Nexvio Phase 3A — Add-on Foundation

## Repository architecture inspected
- The supplied URL without the trailing hyphen returns 404. The actual repository is `nexarion666-png/Nexvio-`, branch `main`.
- Package/application ID: `com.nexvio.app`; Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, SDK 35, Java 17.
- Existing Compose navigation, screens, IPTV import and Media3 player are in `MainActivity.kt`; `M3uParser.kt` and parser tests are separate.
- IPTV source and favourites use SharedPreferences. No add-on service, catalogue service, database or TMDB client exists yet.
- Media3 ExoPlayer 1.5.1 and HLS module already exist. The app manifest already handles orientation changes. Cleartext networking is allowed only for one existing host; this phase preserves that policy.
- The checked-in Gradle wrapper JAR is absent; existing Actions generates it before building. Latest main-branch workflow succeeded.

## Phase 3A changes
- Added manifest/resource/catalogue models and capability checks.
- Added HTTP(S) manifest fetching on IO dispatchers, schema validation, manifest-version errors, 8s connect / 12s read timeouts, 1 MiB size cap, limited redirects and HTTPS downgrade blocking.
- Persisted add-ons in dedicated SharedPreferences; duplicate URL/ID installs rejected; enable/disable, remove and connection test supported.
- Added Add-ons manager under existing Settings → Content providers route.
- Added capability/URL tests and enabled CI on pull requests.

## Scope and verification
This phase manages manifests only; TMDB, catalogue/stream queries, media playback integration and watch progress remain for later phases. Existing Android cleartext policy may block arbitrary HTTP hosts, so use HTTPS unless a host is already permitted. Branch CI must pass before merge; device testing is still required.
