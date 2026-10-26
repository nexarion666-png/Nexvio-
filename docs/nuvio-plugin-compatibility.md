# Nuvio plugin compatibility

Nexvio executes Nuvio-style provider JavaScript in a restricted Android WebView adapter. It is not a full Node.js, Hermes, or Cheerio implementation. Repository manifests and provider scripts are third-party inputs and must be treated as untrusted code.

## Runtime contract

Provider scripts are downloaded over HTTP(S), capped at 1 MiB, and invoked through `getStreams(tmdbId, mediaType, season, episode)`. Network response bodies are capped at 2 MiB. The bridge blocks localhost and resolved private/loopback/link-local addresses and permits only HTTP(S) URLs. Do not remove these limits when adding compatibility.

## Supported `require()` modules

- `axios`: basic GET, POST, and HEAD adapter.
- `cheerio` and `cheerio-without-node-native`: a limited DOMParser-backed jQuery/Cheerio-style adapter.
- `console`: no-op logging methods.
- `url`: WebView `URL` and `URLSearchParams`.
- `crypto-js` and subpaths are not yet supported by this compatibility layer; providers that require them fail explicitly.
- Other Node built-ins and arbitrary npm modules are unsupported.

The runtime supplies browser/WebView APIs where available, plus a small set of compatibility shims. The Cheerio adapter supports only a subset of selectors and methods; it should not be treated as drop-in Cheerio. The fetch bridge implements a bounded HTTP request adapter, not the complete WHATWG Fetch API.

## Diagnostics and known limitations

Provider diagnostics show the runtime engine stamp and recent sanitized request logs. Request logs omit query strings, headers, bodies, and full stream URLs. A provider may complete without streams when its site layout changes, it blocks automated requests, its extractor does not recognize the response, or a required module/API is unsupported. A timeout is not proof the site has no streams.

The runtime is deliberately restricted. Remote provider JavaScript can still be buggy or malicious, so review repositories before enabling them and disable providers you do not trust. Treat stream URLs and returned metadata as untrusted.

## Licensing note

Nuvio-compatible manifests and provider scripts are third-party content. This adapter is maintained independently; review each upstream project's license and terms before redistributing bundled provider code or adapting upstream runtime code.
