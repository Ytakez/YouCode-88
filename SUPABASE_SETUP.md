# YouCode 1.3.6 — Supabase backend foundation

Supabase project URL: `https://gzuzyagiqgvpeqkksaml.supabase.co`

The URL supplied as `/rest/v1/` has been normalized to the project root. The `sb_publishable_...` key is a client publishable key, not an admin credential.

## Setup
1. Supabase Dashboard → SQL Editor → run `supabase/setup.sql`.
2. Enable Email authentication in Authentication → Providers.
3. Sign in once through Telegram (see `TELEGRAM_SETUP.md`) to create the owner account.
4. In Authentication → Users, copy the owner's UUID and run the final UPDATE statement in `setup.sql` to grant admin role.
5. Never place `service_role` / `sb_secret` in the Android app or public repository.

## Security and limits
If you ran an older `setup.sql`, run the new one again: it is idempotent and fixes (1) clients being able to edit their own limits, role and `is_active` (now only `username` is editable) and (2) signup failing when two emails share the same part before `@`.

The schema creates per-user profiles, chat sessions, messages, and usage records with Row Level Security. Clients cannot insert usage events or edit their own role/limits. A trusted Edge Function or gateway must validate the Supabase JWT, enforce quotas atomically, call OpenCode through an authenticated tunnel, and write usage using server-side credentials.

A phone generally has one shared public egress IP for all app users. Per-user quotas are application-level limits, not unique public IPs. Do not expose the OpenCode port directly to the internet. The phone must remain online and Android must keep the foreground service alive.

## Implementation status
This package adds the database/RLS foundation only. Android sign-in UI and the metered public AI gateway are not complete; per-user quotas are not enforced until every model request passes through that gateway. Existing local chat behavior remains unchanged.
