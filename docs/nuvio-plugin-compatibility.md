# Nuvio plugin compatibility

Nexvio treats Nuvio JavaScript-plugin repositories as a separate format from Stremio add-ons.

## Manifest shape

A repository manifest has root `name` and `version` fields and a `scrapers` array. Each provider entry can include `id`, `name`, `description`, `version`, `filename`, `enabled`, `hasSettings`, `supportedTypes`, `formats`, `contentLanguage`, `author`, `limited`, `notes`, `disabledPlatforms`, and `supportsExternalPlayer`.

The parser added in this change recognizes these fields, resolves each `filename` relative to the HTTPS manifest URL, applies size/count limits, and keeps optional metadata optional.

## Important current limitation

This change is manifest compatibility only. It does **not** execute downloaded provider JavaScript, implement Nuvio's `getStreams(tmdbId, mediaType, season, episode)` contract, or make plugin results playable. The Nuvio runtime also provides JavaScript polyfills and modules (including `fetch`, timers, URL/base64/crypto helpers, HTML parsing and `require` shims); parsing a manifest alone cannot provide that behavior.

The next implementation step is a separately reviewed JavaScript runtime with bounded execution, restricted host bindings, provider settings, and a normalized stream result model connected to Nexvio's existing Media3 player. Remote JavaScript must be treated as untrusted code. Nexvio should not claim full Nuvio compatibility until that end-to-end path is implemented and tested.

## Upstream reference

The Nuvio project is GPL-3.0 licensed. This compatibility layer was written independently and does not copy Nuvio runtime source. Review upstream license obligations before adapting or distributing any upstream implementation.
