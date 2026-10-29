-- Nexvio security fix: anonymous roles must not have table-level mutation/read grants.
-- RLS policies already limit rows to auth.uid() for authenticated users; however,
-- anonymous role currently has direct privileges on both account-scoped tables.
-- This revokes those table grants without altering schema, data, or authenticated grants.

REVOKE ALL PRIVILEGES ON TABLE public.nexvio_profiles FROM anon;
REVOKE ALL PRIVILEGES ON TABLE public.nexvio_profile_state FROM anon;

-- Authenticated users keep SELECT/INSERT/UPDATE/DELETE; RLS continues to enforce
-- account_id = auth.uid() for both USING and WITH CHECK.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.nexvio_profiles TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.nexvio_profile_state TO authenticated;
