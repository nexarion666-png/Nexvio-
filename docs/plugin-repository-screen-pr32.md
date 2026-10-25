# Plugin repository screen — PR #32

## What this adds

- Settings → Content providers opens a plugin repository management screen.
- Add repository accepts an HTTPS JSON manifest URL, fetches it with bounded timeouts/size, validates it, and stores the manifest metadata.
- Saved repositories show their name, version, description, manifest URL, and provider names.
- Repositories can be enabled/disabled or removed. State is persisted by PluginRepositoryStore.
- Clear errors are shown for insecure URLs, failed requests, invalid JSON, and unsupported manifests.

## Manifest shape currently understood

The parser expects a top-level `name` and either a `providers` or `scrapers` array. Each usable provider needs a `name` and a `file` (or `filename`) URL. Provider URLs may be relative to the manifest URL, but must resolve to HTTPS.

```json
{
  "name": "Example repository",
  "description": "Example metadata only",
  "version": "1.0.0",
  "providers": [
    {
      "id": "example-provider",
      "name": "Example provider",
      "description": "Metadata-only sample",
      "file": "providers/example.js"
    }
  ]
}
```

This is a description of Nexvio's current parser shape, not a claim that arbitrary Nuvio repositories are compatible. Provider JavaScript is not downloaded or executed, and no media streams are discovered or played by this PR.

## Manual test checklist

1. Open Settings → Content providers → Plugin repositories.
2. Tap Add repository; confirm the URL field and Add/Cancel controls appear.
3. Submit an HTTP URL and confirm it is rejected without making a request.
4. Submit an invalid/non-JSON HTTPS URL and confirm an understandable error appears.
5. Submit a valid HTTPS manifest matching the shape above; confirm repository metadata and provider names appear.
6. Leave and reopen the screen; confirm the repository persists.
7. Disable and re-enable the repository; confirm the action is reflected after reopening.
8. Remove the repository; confirm it disappears and stays removed after reopening.
9. Confirm existing Home, Search, Live TV, Library, and playback flows still work.

## Security and scope

- Manifest URLs and provider file URLs are HTTPS-only.
- Manifest response size is bounded and HTTP requests have connect/read timeouts.
- Remote JavaScript is not executed in this PR. Treat third-party plugin code as untrusted; a separate sandboxed runtime and compatibility review are required before execution.
