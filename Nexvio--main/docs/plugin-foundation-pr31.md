# Nexvio plugin foundation (PR #31)

This change adds the metadata and repository-management foundation for a future provider-plugin system:

- Models for plugin repositories, manifests, and individual providers.
- HTTPS-only URL validation and safe relative provider URL resolution.
- Bounded manifest fetching (1 MB maximum) with connection/read timeouts and redirects disabled.
- Manifest parsing with limits on input size and provider count.
- Local persistence for repositories and per-repository/per-provider enabled state.
- Unit tests for URL policy and relative URL resolution.

## Deliberate scope boundary

This PR does **not** execute JavaScript, download provider source files, or claim compatibility with every Nuvio plugin. Provider execution is intentionally deferred until the exact provider contract and an isolated runtime can be reviewed. Only HTTPS repository and provider URLs are accepted in this foundation.

The persistence implementation stores repository manifest JSON in app-private SharedPreferences. No repository or viewing data is sent to a Nexvio server.

## Validation

Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` in Android CI. The authoring environment did not execute a full Android build, so passing build status must come from GitHub Actions.
