-- Restrict both cloud tables to the DML operations the clients require.
-- TRUNCATE bypasses row-level security, so it must not remain granted to authenticated.
REVOKE ALL PRIVILEGES ON TABLE public.nexvio_profiles FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE public.nexvio_profile_state FROM anon, authenticated;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.nexvio_profiles TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.nexvio_profile_state TO authenticated;
