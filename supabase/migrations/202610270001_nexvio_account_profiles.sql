-- Run this migration in the Supabase SQL Editor for the Nexvio project.
-- Rows are scoped to the authenticated account. Never disable RLS on these tables.
create table if not exists public.nexvio_profiles (
    account_id uuid not null references auth.users(id) on delete cascade,
    profile_id text not null,
    profile_data jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (account_id, profile_id)
);

create table if not exists public.nexvio_profile_state (
    account_id uuid not null references auth.users(id) on delete cascade,
    profile_id text not null,
    state_key text not null,
    state_data jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (account_id, profile_id, state_key),
    foreign key (account_id, profile_id)
        references public.nexvio_profiles(account_id, profile_id)
        on delete cascade
);

alter table public.nexvio_profiles enable row level security;
alter table public.nexvio_profile_state enable row level security;

drop policy if exists "Users manage their own Nexvio profiles" on public.nexvio_profiles;
create policy "Users manage their own Nexvio profiles"
    on public.nexvio_profiles for all
    to authenticated
    using (auth.uid() = account_id)
    with check (auth.uid() = account_id);

drop policy if exists "Users manage their own Nexvio profile state" on public.nexvio_profile_state;
create policy "Users manage their own Nexvio profile state"
    on public.nexvio_profile_state for all
    to authenticated
    using (auth.uid() = account_id)
    with check (auth.uid() = account_id);

create index if not exists nexvio_profile_state_updated_at_idx
    on public.nexvio_profile_state (account_id, updated_at desc);
