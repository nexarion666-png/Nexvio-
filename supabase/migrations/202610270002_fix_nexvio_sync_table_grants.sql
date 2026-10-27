-- Repair table privileges for Nexvio's authenticated cloud-sync client.
-- RLS policies still restrict every row to auth.uid() = account_id.
grant usage on schema public to authenticated;
grant select, insert, update, delete on table public.nexvio_profiles to authenticated;
grant select, insert, update, delete on table public.nexvio_profile_state to authenticated;
