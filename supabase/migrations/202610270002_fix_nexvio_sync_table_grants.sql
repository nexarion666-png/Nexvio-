-- Repair table privileges for Nexvio's authenticated cloud-sync client.
-- Row-level security policies must remain enabled; these grants do not bypass RLS.
grant usage on schema public to authenticated;
grant select, insert, update, delete on table public.nexvio_profiles to authenticated;
grant select, insert, update, delete on table public.nexvio_profile_state to authenticated;
