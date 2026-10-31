# Nexvio Web

This is the Nexvio web client, kept beside the existing Android app in the same repository. It uses React, TypeScript and Vite; it is not a rewrite of the Android project.

## Deploy on Vercel

1. Import the existing Nexvio GitHub repository into Vercel.
2. Set **Root Directory** to `web`.
3. Use the Vite framework preset. Build command: `npm run build`; output directory: `dist`.
4. Add the environment variables below to Preview and Production, then redeploy.

## Environment variables

Copy `.env.example` to `.env.local` for local development and fill in:

- `VITE_SUPABASE_URL`: the existing Nexvio Supabase project URL.
- `VITE_SUPABASE_PUBLISHABLE_KEY`: that same project's publishable client key. **Never use a Supabase service-role/secret key in a browser app.**
- `VITE_TMDB_API_KEY`: the TMDB API key used for the catalogue.

In Supabase Auth settings, add the Vercel production domain and preview domains that you intend to use to the allowed redirect/site URL configuration. Password reset and email confirmation links must return to the web app.

## Current milestone

- Responsive Nexvio-inspired web shell for mobile and desktop.
- Supabase sign-up, sign-in, session persistence, sign-out, email-confirmation redirects, password-reset email, and password recovery completion using the same project as Android.
- Live TMDB trending/popular catalogue, title search, detail cards and initial watchlist UI.
- Vercel SPA routing configuration.
- Stage 3 live schema/RLS/grant audit recorded in [docs/backend-audit.md](docs/backend-audit.md).

## Stage 3 cloud sync status

The live Supabase tables match the Android client schema and both have authenticated owner RLS policies. The `anon` table grants have been revoked in the connected project and verified with live privilege checks; the migration is tracked in PR #109 for repository history. The Supabase security advisor still flags disabled leaked-password protection.

Stage 3 now connects My List to the existing `nexvio_profile_state` table using `profile_id = primary` and `state_key = watchlist`. It stores the Android-compatible `items` preference as a JSON string, including an explicit empty array when items are removed, and shows cloud load/save/error status. The build and schema/grant checks are being run on the Stage 3 PR. Refresh persistence and real two-account browser isolation still need end-to-end runtime verification; no new web-only watchlist table is planned.

## Not yet implemented

This foundation does not claim full feature parity yet. Browser provider execution and real stream playback, verified profile-scoped cloud sync, shared history/preferences, add-on management, downloads, and release/update parity still need dedicated implementation and testing.

## Stage 4 — cloud-synced preferences

The Stage 4 branch adds a Preferences screen backed by the existing `nexvio_profile_state` row (`profile_id=primary`, `state_key=settings`). It stores web-owned keys (`web_autoplay_previews`, `web_remember_progress`) while preserving other keys in the state object, with account-session checks and visible sync/error status. Autoplay and playback-progress behavior are preference placeholders until browser playback is implemented; they do not claim to control a player yet. This feature is additive and does not modify Android source or create a new table.
