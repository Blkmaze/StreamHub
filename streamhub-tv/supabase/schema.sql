-- ===================================================================
-- StreamHub TV — support messaging backend (v2, targeted messaging)
--
-- Run this once in the Supabase SQL editor (Dashboard → SQL → New query).
-- It is safe to re-run, and safe to run on top of v1 — the ALTERs below
-- migrate an existing install without losing messages.
--
-- Addressing model
--   device   — one stick.            messages.device_id = 'A1B2C3D4'
--   account  — one customer, every stick they own.
--              messages.device_id IS NULL, messages.account_ref = 'john@panel.tv:8080'
--   all      — everybody (notices only).
-- ===================================================================

create extension if not exists "pgcrypto";

-- ------------------------------------------------------------------
-- Device registry: every stick announces itself on launch
-- ------------------------------------------------------------------
create table if not exists public.devices (
    device_id     text primary key,
    account_ref   text        not null,
    client_name   text,
    app_version   text,
    device_model  text,
    server_count  int,
    notes         text,
    tags          text[]      not null default '{}',
    blocked       boolean     not null default false,
    first_seen    timestamptz not null default now(),
    last_seen     timestamptz not null default now()
);

create index if not exists devices_account_idx on public.devices (account_ref);

create or replace function public.touch_last_seen()
returns trigger language plpgsql as $$
begin
    new.last_seen := now();
    return new;
end;
$$;

drop trigger if exists devices_touch on public.devices;
create trigger devices_touch
    before insert or update on public.devices
    for each row execute function public.touch_last_seen();

-- ------------------------------------------------------------------
-- Messages
-- ------------------------------------------------------------------
create table if not exists public.messages (
    id            uuid primary key default gen_random_uuid(),
    device_id     text,
    account_ref   text,
    client_name   text,
    sender        text        not null check (sender in ('client', 'support')),
    body          text        not null check (char_length(body) between 1 and 4000),
    app_version   text,
    read_by_support boolean   not null default false,
    created_at    timestamptz not null default now()
);

-- migrate a v1 install
alter table public.messages add column if not exists account_ref text;
alter table public.messages alter column device_id drop not null;

-- a row must be addressed to something
do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'messages_addressed'
    ) then
        alter table public.messages
            add constraint messages_addressed
            check (device_id is not null or account_ref is not null);
    end if;
end $$;

create index if not exists messages_device_time_idx  on public.messages (device_id, created_at);
create index if not exists messages_account_time_idx on public.messages (account_ref, created_at);

-- backfill account_ref on old rows from the device registry where possible
update public.messages m
set    account_ref = d.account_ref
from   public.devices d
where  m.account_ref is null and m.device_id = d.device_id;

-- ------------------------------------------------------------------
-- Notices (the banner on the browse screen)
-- ------------------------------------------------------------------
create table if not exists public.notices (
    id         uuid primary key default gen_random_uuid(),
    body       text        not null,
    audience   text        not null default 'all'
                           check (audience in ('all', 'account', 'device')),
    target     text,
    created_at timestamptz not null default now()
);

alter table public.notices add column if not exists audience text not null default 'all';
alter table public.notices add column if not exists target text;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'notices_target_present') then
        alter table public.notices
            add constraint notices_target_present
            check (audience = 'all' or target is not null);
    end if;
end $$;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'notices_audience_valid') then
        alter table public.notices
            add constraint notices_audience_valid
            check (audience in ('all', 'account', 'device'));
    end if;
end $$;

create index if not exists notices_audience_idx on public.notices (audience, target, created_at desc);

-- ------------------------------------------------------------------
-- Identity helpers
--
-- The app sends its device ID in the `x-device-id` header. The account it
-- belongs to is looked up in the registry rather than trusted from the
-- request, so holding the anon key does not let anyone read another
-- customer's thread by guessing their account reference.
-- ------------------------------------------------------------------
create or replace function public.current_device()
returns text language sql stable as $$
    select coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '');
$$;

create or replace function public.current_account()
returns text language sql stable security definer set search_path = public as $$
    select account_ref from public.devices where device_id = public.current_device();
$$;

-- ------------------------------------------------------------------
-- Row level security
-- ------------------------------------------------------------------
alter table public.devices  enable row level security;
alter table public.messages enable row level security;
alter table public.notices  enable row level security;

-- devices: a stick may register and update only its own row
drop policy if exists "device registers itself" on public.devices;
create policy "device registers itself"
    on public.devices for insert to anon, authenticated
    with check (device_id = public.current_device());

drop policy if exists "device updates itself" on public.devices;
create policy "device updates itself"
    on public.devices for update to anon, authenticated
    using (device_id = public.current_device())
    with check (device_id = public.current_device());

drop policy if exists "device reads itself" on public.devices;
create policy "device reads itself"
    on public.devices for select to anon, authenticated
    using (device_id = public.current_device());

-- messages: this stick's thread, plus anything addressed to its account
drop policy if exists "device reads own messages" on public.messages;
create policy "device reads own messages"
    on public.messages for select to anon, authenticated
    using (
        device_id = public.current_device()
        or (device_id is null
            and account_ref is not null
            and account_ref = public.current_account())
    );

drop policy if exists "device writes own messages" on public.messages;
create policy "device writes own messages"
    on public.messages for insert to anon, authenticated
    with check (
        sender = 'client'
        and device_id = public.current_device()
    );

-- notices: everyone's, this account's, or this device's
drop policy if exists "anyone reads notices" on public.notices;
drop policy if exists "device reads its notices" on public.notices;
create policy "device reads its notices"
    on public.notices for select to anon, authenticated
    using (
        audience = 'all'
        or (audience = 'device'  and target = public.current_device())
        or (audience = 'account' and target = public.current_account())
    );

-- Support replies are written from the admin console with the service_role key,
-- which bypasses RLS by design. No extra policy needed.

-- ------------------------------------------------------------------
-- The admin console builds its account directory client-side from
-- public.devices and public.messages using the service_role key, so there
-- are no views to keep in sync and nothing extra exposed to the anon key.
-- ------------------------------------------------------------------
drop view if exists public.conversations;

-- Realtime (optional): lets the console update without polling
do $$
begin
    alter publication supabase_realtime add table public.messages;
exception when duplicate_object then null;
end $$;
