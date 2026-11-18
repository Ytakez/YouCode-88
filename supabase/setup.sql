-- YouCode 1.3.7 Supabase schema. Run in Supabase Dashboard -> SQL Editor.
create extension if not exists pgcrypto;

create table if not exists public.profiles (
 id uuid primary key references auth.users(id) on delete cascade,
 username text unique,
 role text not null default 'user' check (role in ('user','admin')),
 is_active boolean not null default true,
 daily_request_limit integer not null default 30 check (daily_request_limit >= 0),
 monthly_token_limit bigint not null default 100000 check (monthly_token_limit >= 0),
 created_at timestamptz not null default now()
);
create table if not exists public.chat_sessions (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 title text not null default 'Новый чат', created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists public.chat_messages (
 id uuid primary key default gen_random_uuid(), session_id uuid not null references public.chat_sessions(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 role text not null check (role in ('user','assistant','tool')), content text not null, model_id text,
 input_tokens integer not null default 0 check (input_tokens >= 0), output_tokens integer not null default 0 check (output_tokens >= 0),
 created_at timestamptz not null default now()
);
create table if not exists public.usage_events (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 model_id text not null, request_count integer not null default 1 check (request_count > 0),
 input_tokens integer not null default 0 check (input_tokens >= 0), output_tokens integer not null default 0 check (output_tokens >= 0),
 client_ip inet, created_at timestamptz not null default now()
);
create or replace function public.handle_new_user() returns trigger language plpgsql security definer set search_path=public as $$
declare
 base text;
 candidate text;
 n integer := 0;
begin
 base := coalesce(nullif(new.raw_user_meta_data->>'username',''), split_part(new.email,'@',1));
 candidate := base;
 -- username is unique: ivan@gmail.com and ivan@mail.ru must both be able to sign up
 while exists(select 1 from public.profiles where username = candidate) loop
  n := n + 1;
  candidate := base || n::text;
 end loop;
 begin
  insert into public.profiles(id,username) values(new.id,candidate) on conflict(id) do nothing;
 exception when unique_violation then
  insert into public.profiles(id,username) values(new.id, base || '_' || substr(new.id::text,1,8)) on conflict(id) do nothing;
 end;
 return new;
end; $$;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users for each row execute procedure public.handle_new_user();
create or replace function public.is_admin() returns boolean language sql stable security definer set search_path=public as $$
 select exists(select 1 from public.profiles where id=auth.uid() and role='admin' and is_active=true);
$$;
alter table public.profiles enable row level security;
alter table public.chat_sessions enable row level security;
alter table public.chat_messages enable row level security;
alter table public.usage_events enable row level security;
drop policy if exists "profile self read" on public.profiles;
create policy "profile self read" on public.profiles for select to authenticated using(id=auth.uid() or public.is_admin());
drop policy if exists "profile self update safe" on public.profiles;
create policy "profile self update safe" on public.profiles for update to authenticated using(id=auth.uid()) with check(id=auth.uid());
-- RLS cannot restrict columns: without this a user could raise their own limits / role / is_active.
-- Only username is editable from the client; role and limits change via SQL Editor or a trusted gateway.
revoke update on public.profiles from anon, authenticated;
grant update (username) on public.profiles to authenticated;
drop policy if exists "sessions own read" on public.chat_sessions;
create policy "sessions own read" on public.chat_sessions for select to authenticated using(user_id=auth.uid() or public.is_admin());
drop policy if exists "sessions own insert" on public.chat_sessions;
create policy "sessions own insert" on public.chat_sessions for insert to authenticated with check(user_id=auth.uid());
drop policy if exists "sessions own update" on public.chat_sessions;
create policy "sessions own update" on public.chat_sessions for update to authenticated using(user_id=auth.uid() or public.is_admin()) with check(user_id=auth.uid() or public.is_admin());
drop policy if exists "sessions own delete" on public.chat_sessions;
create policy "sessions own delete" on public.chat_sessions for delete to authenticated using(user_id=auth.uid() or public.is_admin());
drop policy if exists "messages own read" on public.chat_messages;
create policy "messages own read" on public.chat_messages for select to authenticated using(user_id=auth.uid() or public.is_admin());
drop policy if exists "messages own insert" on public.chat_messages;
create policy "messages own insert" on public.chat_messages for insert to authenticated with check(user_id=auth.uid() and exists(select 1 from public.chat_sessions s where s.id=session_id and s.user_id=auth.uid()));
drop policy if exists "messages own delete" on public.chat_messages;
create policy "messages own delete" on public.chat_messages for delete to authenticated using(user_id=auth.uid() or public.is_admin());
drop policy if exists "usage own read" on public.usage_events;
create policy "usage own read" on public.usage_events for select to authenticated using(user_id=auth.uid() or public.is_admin());
-- Telegram login: one-time requests. Only Edge Functions (service role) touch this table.
create table if not exists public.telegram_login_requests (
 token text primary key,
 telegram_id bigint,
 telegram_username text,
 first_name text,
 created_at timestamptz not null default now(),
 confirmed_at timestamptz
);
alter table public.telegram_login_requests enable row level security;
revoke all on public.telegram_login_requests from anon, authenticated;
-- No client insert/update policies for usage_events: only a trusted gateway may write usage.
-- After the owner's first account registers, promote it manually:
-- update public.profiles set role='admin' where id='YOUR-AUTH-USER-UUID';
