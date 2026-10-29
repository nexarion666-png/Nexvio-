# Nexvio Web Parity Implementation Report

Date: 2026-10-29
Base snapshot: `965a4bcb1529d2cd70dcb9b0167be237f21148fa` (confirmed from repository `main` before edits)

## Implemented in this working snapshot

- Changed the primary navigation to Android's five destinations: Home, Search, Live TV, Library, and Profile. Added a fixed touch-friendly mobile navigation bar and kept desktop navigation.
- Changed the default web design token to the Android default Emerald accent (`#38C77A`) and Android background (`#101114`), with responsive styling for profile, library, IPTV, and player views.
- Search now uses separate TMDB movie and TV search endpoints. Added category switching and detailed metadata fetch for genre/runtime/season counts where TMDB supplies them.
- Removed the title-details fake Play alert. The details view explains that TMDB is metadata-only and playback is unavailable until a compatible provider/source exists.
- Added account-owned profile list loading/creation against `nexvio_profiles`, profile switching, profile-scoped watchlist/settings reads and writes, and browser-local active-profile selection.
- Hardened readiness markers to use account+profile identity so a prior profile's watchlist is not treated as loaded for a newly selected profile.
- Added M3U/M3U8 file import, URL import (subject to CORS), channel logos/groups, filtering, favorites, local browser persistence, and an HTML5 video player with honest playback errors. Imported playlist contents are not uploaded to cloud state.
- Added a working Reduce Motion preference that applies to the browser UI and syncs under a web-owned key while preserving unrelated Android settings keys.
- Added a feature-parity audit and a Node built-in test suite for M3U parsing.

## Tests actually run

- `npm test`: **passed**, 3/3 M3U parser tests.
- TypeScript `transpileModule` syntax checks for `main.tsx`, Supabase helpers, and M3U helper declaration: **passed** (syntax/transpilation only; not a full type check).
- `npm run build`: **not verified**. It was attempted, but dependency installation could not complete because the package registry returned `EAI_AGAIN`; consequently React, Supabase, Vite, and project type declarations were unavailable locally. The reported TypeScript errors from that attempt are dominated by missing packages/types and cannot be treated as a valid full-project build result.
- Browser smoke tests, authentication/profile runtime tests, TMDB network tests, IPTV stream playback tests, and cross-account isolation tests: **not run**.
- Android build and updater deployment: not run; no Android or `updates-site/` files were modified.

## Known gaps; not production-ready yet

- The browser still has no movie/series stream provider adapter. Title metadata must not be mistaken for playable content.
- Browser video supports only formats/codecs the browser can play. No HLS/DASH JavaScript adapter is included. HLS may work natively in some browsers; many providers block playlist fetch or media requests through CORS or require custom headers the browser cannot set.
- Watch history, continue-watching, playback progress persistence, subtitle/audio track selection, playback speed controls, EPG, downloads, notifications, theme picker/AMOLED setting, Android layout settings, add-on/plugin repository execution, profile edit/delete/avatar management, and full Android cloud-state keys are not implemented in this stage.
- Profile active selection is browser-local. The profile records and profile-scoped watchlist/settings are cloud-backed, but cross-device active-profile selection and profile deletion tombstones are not.
- No two-user RLS integration test was run. Existing RLS was not modified by these web changes.
- Full TypeScript checking/build and Vercel deployment remain required before merging or production use.

## Protected scope

Changes are confined to `web/`. Android source under `app/`, the Android update site under `updates-site/`, workflows, production configuration, and Supabase schema/policies were not modified.
