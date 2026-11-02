create table if not exists public.nexvio_analytics_events (
    id bigint generated always as identity primary key,
    installation_id uuid not null,
    event_type text not null check (event_type = 'app_open'),
    app_version text not null,
    occurred_at timestamptz not null,
    received_at timestamptz not null default now()
);

alter table public.nexvio_analytics_events enable row level security;
