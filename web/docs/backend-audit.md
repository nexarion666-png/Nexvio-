# Nexvio web backend audit — Stage 2

## Scope and evidence

This is a read-only code audit of the current repository. It is **not** a live Supabase dashboard/database audit: this environment does not have a connected Supabase administration session, so the actual deployed table definitions, grants, and Row Level Security (RLS) policies have not been independently verified.

No database tables, policies, or migrations are changed by this stage.

## What the Android client currently expects

The Android implementation in `app/src/main/java/com/nexvio/app/NexvioCloudSync.kt` calls Supabase Auth to resolve the signed-in user, then accesses these tables through the REST API using the user's access token and the publishable key:

### `nexvio_profiles`

Fields referenced by the client:
- `account_id`
- `profile_id`
- `profile_data` (JSON object)
- `updated_at`

The client performs an upsert with conflict target `account_id,profile_id`.

### `nexvio_profile_state`

Fields referenced by the client:
- `account_id`
- `profile_id`
- `state_key`
- `state_data` (JSON object)
- `updated_at`

The client performs an upsert with conflict target `account_id,profile_id,state_key`.

The client currently uses these state keys for profile-owned data: `settings`, `watchlist`, and `watch_history`. It also uses `shared_addons`, `shared_plugin_repositories`, and `shared_iptv` in the `primary` profile bucket. This describes client behavior, not a verified database schema or an endorsement of syncing every value to the cloud.

## Required live checks before adding web cloud sync

In the Supabase Dashboard for the existing project, inspect **Database → Tables**, **Authentication → Policies**, and the SQL editor. Verify:

1. Both tables exist and their column types match the fields above.
2. The unique constraints required by each upsert conflict target exist.
3. RLS is enabled on both tables.
4. Every SELECT/INSERT/UPDATE/DELETE policy scopes rows to the authenticated owner. The expected ownership relationship is `account_id = auth.uid()`, but confirm the deployed schema and policy expressions rather than assuming them.
5. Anonymous users cannot read or mutate account rows.
6. A signed-in user A cannot read, insert, update, or delete user B's rows, including by submitting B's `account_id`.
7. Table grants do not bypass the intended RLS restrictions.
8. No service-role or secret key is embedded in web source, Vite `VITE_*` variables, client bundles, or Android client configuration.
9. Confirm data-retention expectations and whether shared add-on/provider settings may contain private URLs or device-local identifiers. Device-specific document URIs must not be synchronized.

Do not create replacement tables or loosen policies to make a web request work. If a check fails, design a minimal migration/policy correction only after reviewing the current policy definitions and preserving Android compatibility.

## Web authentication changes in Stage 2

The web client now:
- Uses the same project URL and publishable client key supplied through environment variables.
- Requests email-confirmation and password-recovery redirects back to the current web origin.
- Handles the Supabase `PASSWORD_RECOVERY` event and presents a new-password form.
- Updates the password through Supabase Auth, then signs out so the user can sign in with the new password.
- Handles session restoration errors instead of leaving the loading screen stuck indefinitely.

These are code-level changes. Real email confirmation and password recovery still require the actual production/preview domains to be allow-listed in Supabase Auth URL configuration and tested using a real account.

## Current decision

**Do not implement web writes to `nexvio_profiles` or `nexvio_profile_state` yet.** First complete the live RLS/schema checks above. Until then, web authentication and Android compatibility can be built/tested, but shared cloud data must remain unimplemented.
