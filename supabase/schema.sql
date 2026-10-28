-- HyperMagic social: profiles, friends, direct messages, XP.
-- Run once in the Supabase dashboard: SQL Editor > New query > paste > Run.
-- Calls and screen sharing don't need tables: they are signalled over Realtime broadcast and the
-- audio/video goes directly between the phones (WebRTC).

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique not null check (username ~ '^[a-z0-9_.]{3,20}$'),
  display_name text not null default '',
  color int not null default 0,
  status text not null default '',
  xp int not null default 0,
  level int not null default 1,
  playing text not null default '',
  last_seen timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create table if not exists public.friendships (
  requester uuid not null references public.profiles(id) on delete cascade,
  addressee uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted')),
  created_at timestamptz not null default now(),
  primary key (requester, addressee),
  check (requester <> addressee)
);

create table if not exists public.messages (
  id bigint generated always as identity primary key,
  sender uuid not null references public.profiles(id) on delete cascade,
  receiver uuid not null references public.profiles(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 2000),
  created_at timestamptz not null default now(),
  read_at timestamptz
);
create index if not exists messages_pair on public.messages (least(sender, receiver), greatest(sender, receiver), created_at desc);
create index if not exists messages_receiver on public.messages (receiver, read_at);

alter table public.profiles enable row level security;
alter table public.friendships enable row level security;
alter table public.messages enable row level security;

-- profiles: anyone signed in can find people by username; you edit only your own
drop policy if exists "profiles read" on public.profiles;
create policy "profiles read" on public.profiles for select to authenticated using (true);
drop policy if exists "profiles insert own" on public.profiles;
create policy "profiles insert own" on public.profiles for insert to authenticated with check (id = auth.uid());
drop policy if exists "profiles update own" on public.profiles;
create policy "profiles update own" on public.profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- friendships: both sides see it; you send requests as yourself; the addressee accepts; either removes
drop policy if exists "friends read" on public.friendships;
create policy "friends read" on public.friendships for select to authenticated using (auth.uid() in (requester, addressee));
drop policy if exists "friends request" on public.friendships;
create policy "friends request" on public.friendships for insert to authenticated with check (requester = auth.uid() and status = 'pending');
drop policy if exists "friends accept" on public.friendships;
create policy "friends accept" on public.friendships for update to authenticated using (addressee = auth.uid()) with check (addressee = auth.uid());
drop policy if exists "friends remove" on public.friendships;
create policy "friends remove" on public.friendships for delete to authenticated using (auth.uid() in (requester, addressee));

-- messages: only between friends; you read your own conversations; the receiver marks as read
create or replace function public.are_friends(a uuid, b uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from friendships f where f.status = 'accepted'
    and ((f.requester = a and f.addressee = b) or (f.requester = b and f.addressee = a)));
$$;
drop policy if exists "messages read" on public.messages;
create policy "messages read" on public.messages for select to authenticated using (auth.uid() in (sender, receiver));
drop policy if exists "messages send" on public.messages;
create policy "messages send" on public.messages for insert to authenticated with check (sender = auth.uid() and public.are_friends(sender, receiver));
drop policy if exists "messages mark read" on public.messages;
create policy "messages mark read" on public.messages for update to authenticated using (receiver = auth.uid()) with check (receiver = auth.uid());

-- new messages and friend requests arrive live (Realtime "postgres_changes")
do $$ begin
  alter publication supabase_realtime add table public.messages;
exception when duplicate_object then null; end $$;
do $$ begin
  alter publication supabase_realtime add table public.friendships;
exception when duplicate_object then null; end $$;
