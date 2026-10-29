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

## Security issue found — migration proposed, not applied

The live table ACLs grant the `anon` role direct SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, and TRIGGER privileges on both tables. RLS policies are currently scoped to `authenticated`, but the anonymous grants are over-broad and must be removed. The database also reports a Supabase Auth advisor warning that leaked-password protection is disabled.

A narrowly scoped migration has been proposed in [PR #109](https://github.com/nexarion666-png/Nexvio-/pull/109):
- Revoke all table privileges from `anon` on both tables.
- Grant only SELECT/INSERT/UPDATE/DELETE to `authenticated`, preserving Android's current REST sync operations.
- Leave the current RLS policies and data untouched.

**Do not enable web cloud sync until PR #109 is reviewed and the migration is applied.** After application, re-query the actual table grants and policies, then perform authenticated two-account isolation tests. Do not run writes as a service-role or postgres test because that bypasses normal user RLS behavior.

## Android compatibility

The proposed migration does not change the tables or the authenticated grants required for Android to read, upsert, or delete rows. Nevertheless, Android sync must be regression-tested after migration application. No Android source files were modified.

## Other findings and limits

- The security advisor reports `auth_leaked_password_protection` is disabled; consider enabling it in Supabase Auth settings.
- The project currently shows no migration history through the Supabase migration-list endpoint, even though tables exist. Establish a migration baseline carefully rather than assuming the history is complete.
- The publishable client key is intended for browser use; do not add any service-role/secret key to `VITE_*` environment variables or web bundles.
- Existing state keys include `settings`, `watchlist`, `watch_history`, `shared_addons`, `shared_plugin_repositories`, and `shared_iptv`. Only web-safe fields should be synchronized. Device-specific file URIs and local downloaded media are not portable.
- This audit has not yet run live browser HTTP tests with separate user accounts. RLS ownership expressions and schema shape are confirmed from metadata; end-to-end account isolation still needs testing.

## Stage 3 implementation gate

After the grant migration is merged/applied and independently re-verified:
1. Read existing primary profile and its `watchlist` state for the signed-in account.
2. Preserve the existing Android watchlist JSON schema and identifiers; do not create a separate web-only table.
3. Implement authenticated upsert for that same state key, with explicit empty-list semantics for deletions.
4. Add loading/error/sync status and prevent stale requests from a previous session from updating a newly signed-in account.
5. Test refresh persistence and two-account isolation.
6. Extend carefully to history/settings after the watchlist path is validated.
