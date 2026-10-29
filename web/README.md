# Nexvio Web

This is the first web foundation for Nexvio, kept beside the existing Android app in the same repository. It uses React, TypeScript and Vite; it is not a rewrite of the Android project.

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

## Not yet implemented

This foundation does **not** claim full feature parity yet. Browser provider execution and real stream playback, profile-scoped cloud sync, shared watchlist/history/preferences, add-on management, downloads, and release/update parity still need dedicated implementation and testing. The Android app's existing cloud sync currently uses `nexvio_profiles` and `nexvio_profile_state` for profile metadata and profile-scoped state. The repository-level expectations and required live schema/RLS checks are documented in [`docs/backend-audit.md`](docs/backend-audit.md). The deployed Supabase schema and policies have not been independently inspected, so web writes to those tables remain blocked until the live audit is complete. Browser storage is not being presented as cloud sync.
