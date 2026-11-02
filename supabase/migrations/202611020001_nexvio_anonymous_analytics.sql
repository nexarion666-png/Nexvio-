-- Anonymous Nexvio app-open analytics. No account IDs or personal data are collected.
create table if not exists public.nexvio_analytics_events (
    id bigint generated always as identity primary key,
    installation_id uuid not null,
    event_type text not null check (event_type = 'app_open'),
    app_version text not null check (app_version ~ '^[0-9]+(\.[0-9]+){0,3}$'),
    occurred_at timestamptz not null,
    received_at timestamptz not null default now(),
    constraint nexvio_analytics_event_time_reasonable
        check (occurred_at >= now() - interval '10 minutes'
           and occurred_at <= now() + interval '2 minutes')
);

create index if not exists nexvio_analytics_events_received_at_idx
    on public.nexvio_analytics_events (received_at desc);
create index if not exists nexvio_analytics_events_installation_received_idx
    on public.nexvio_analytics_events (installation_id, received_at desc);

alter table public.nexvio_analytics_events enable row level security;

-- Public app clients can only append validated app-open events.
grant insert on table public.nexvio_analytics_events to anon;
grant usage on sequence public.nexvio_analytics_events_id_seq to anon;

drop policy if exists "Allow validated anonymous Nexvio app-open events"
    on public.nexvio_analytics_events;
create policy "Allow validated anonymous Nexvio app-open events"
    on public.nexvio_analytics_events
    for insert
    to anon
    with check (
        event_type = 'app_open'
        and app_version ~ '^[0-9]+(\.[0-9]+){0,3}$'
        and occurred_at >= now() - interval '10 minutes'
        and occurred_at <= now() + interval '2 minutes'
    );

comment on table public.nexvio_analytics_events is
    'Append-only anonymous Nexvio app usage events; no account or personal data.';
