# Nexvio web backend audit — Stage 3 live audit

## Project and evidence

Read-only inspection of the connected Nexvio Supabase project (`lbgssprmmqikzmyifphi`). This audit queried the live `public` schema, table metadata, PostgreSQL policies, grants, and Supabase security advisors on 2026-10-29. No database migration has been applied by this audit.

## Existing Android-compatible schema — confirmed

### `public.nexvio_profiles`
- Columns: `account_id uuid`, `profile_id text`, `profile_data jsonb`, `updated_at timestamptz`.
- Primary key: (`account_id, profile_id`).
- Foreign key: `account_id` references `auth.users(id)`.

### `public.nexvio_profile_state`
- Columns: `account_id uuid`, `profile_id text`, `state_key text`, `state_data jsonb`, `updated_at timestamptz`.
- Primary key: (`account_id, profile_id, state_key`).
- Foreign key: (`account_id, profile_id`) references `public.nexvio_profiles(account_id, profile_id)`.

These columns and keys match the Android client's REST filters and upsert conflict targets.

## RLS — confirmed

Both tables have RLS enabled. The current policies target the `authenticated` role and use:
- `USING (auth.uid() = account_id)`
- `WITH CHECK (auth.uid() = account_id)`

This is the expected row-ownership boundary for normal authenticated reads and writes, including attempted account-id spoofing.

## Security issue found and remediated — verified 2026-10-29

The initial live audit found direct table privileges on both tables granted to `anon`. The SQL revoke migration tracked in [PR #109](https://github.com/nexarion666-png/Nexvio-/pull/109) has since been applied to the connected Supabase project and independently re-queried. Current ACL checks confirm `anon` has no table privileges on either table and `authenticated` has only SELECT/INSERT/UPDATE/DELETE. In particular, TRUNCATE was removed from `authenticated` because it bypasses RLS. The existing RLS policies remain scoped to `authenticated` and enforce `auth.uid() = account_id` in both `USING` and `WITH CHECK`. Both idempotent migrations are tracked in PR #109.

The Supabase security advisor still reports leaked-password protection is disabled. Enable it in Supabase Auth settings as a separate account-security follow-up. A real two-user browser isolation test has not been run yet; the grant and policy metadata are verified, but that end-to-end test remains an explicit validation item.

## Android compatibility

The applied migration does not change the tables or remove the authenticated grants required for Android to read or upsert rows. Android sync should still be regression-tested against the live project. No Android source files were modified.

## Other findings and limits

- The security advisor reports `auth_leaked_password_protection` is disabled; consider enabling it in Supabase Auth settings.
- The project currently shows no migration history through the Supabase migration-list endpoint, even though tables exist. Establish a migration baseline carefully rather than assuming the history is complete.
- The publishable client key is intended for browser use; do not add any service-role/secret key to `VITE_*` environment variables or web bundles.
- Existing state keys include `settings`, `watchlist`, `watch_history`, `shared_addons`, `shared_plugin_repositories`, and `shared_iptv`. Only web-safe fields should be synchronized. Device-specific file URIs and local downloaded media are not portable.
- This audit has not yet run live browser HTTP tests with separate user accounts. RLS ownership expressions, schema shape, and anonymous table privilege revocation are confirmed from live metadata; end-to-end account isolation still needs testing.

## Stage 3 implementation gate

Stage 3 watchlist implementation now uses the existing `nexvio_profiles` and `nexvio_profile_state` tables, `profile_id = primary`, and `state_key = watchlist`; it stores the `items` preference as a JSON string and preserves other state keys. Empty lists are written as `[]` so removals persist. The web app shows load/save/error status and checks the active account before applying results. Build checks and live grant/policy metadata should be re-verified on the final commit. Refresh persistence and two-account browser isolation remain runtime checks that require signing in with separate test accounts.
