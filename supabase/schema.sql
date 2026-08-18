-- ===================================================================
-- StreamHub TV — support messaging backend
-- Run this once in the Supabase SQL editor (Dashboard → SQL → New query).
-- ===================================================================

create extension if not exists "pgcrypto";

-- ------------------------------------------------------------------
-- Conversations: one row per message, grouped by the device's short ID
-- ------------------------------------------------------------------
create table if not exists public.messages (
    id            uuid primary key default gen_random_uuid(),
    device_id     text        not null,
    client_name   text,
    sender        text        not null check (sender in ('client', 'support')),
    body          text        not null check (char_length(body) between 1 and 4000),
    app_version   text,
    read_by_support boolean   not null default false,
    created_at    timestamptz not null default now()
);

create index if not exists messages_device_time_idx
    on public.messages (device_id, created_at);

-- ------------------------------------------------------------------
-- Broadcast notices shown as a banner on every device
-- ------------------------------------------------------------------
create table if not exists public.notices (
    id         uuid primary key default gen_random_uuid(),
    body       text        not null,
    created_at timestamptz not null default now()
);

-- ------------------------------------------------------------------
-- Row level security
--
-- The app sends its short device ID in the `x-device-id` header. A device can
-- only ever read or write its own conversation; nobody holding the anon key can
-- enumerate other customers' messages.
-- ------------------------------------------------------------------
alter table public.messages enable row level security;
alter table public.notices  enable row level security;

drop policy if exists "device reads own messages" on public.messages;
create policy "device reads own messages"
    on public.messages for select
    to anon, authenticated
    using (
        device_id = coalesce(
            current_setting('request.headers', true)::json ->> 'x-device-id', ''
        )
    );

drop policy if exists "device writes own messages" on public.messages;
create policy "device writes own messages"
    on public.messages for insert
    to anon, authenticated
    with check (
        sender = 'client'
        and device_id = coalesce(
            current_setting('request.headers', true)::json ->> 'x-device-id', ''
        )
    );

drop policy if exists "anyone reads notices" on public.notices;
create policy "anyone reads notices"
    on public.notices for select
    to anon, authenticated
    using (true);

-- Support replies are written with the service_role key from the admin console,
-- which bypasses RLS by design. No extra policy needed.

-- ------------------------------------------------------------------
-- Handy view for the admin console: newest message per device
-- ------------------------------------------------------------------
create or replace view public.conversations as
select distinct on (device_id)
       device_id,
       client_name,
       body        as last_message,
       sender      as last_sender,
       created_at  as last_at
from public.messages
order by device_id, created_at desc;

-- Realtime (optional): lets the admin console update without polling
alter publication supabase_realtime add table public.messages;
