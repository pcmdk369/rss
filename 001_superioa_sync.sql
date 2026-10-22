-- OPTIONAL cloud sync schema. The Android MVP does NOT call Supabase yet (local-first).
-- Apply with the Supabase CLI. Row Level Security: each user sees only their own rows.
create table if not exists public.task_history (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade default auth.uid(),
  goal        text not null,
  status      text not null,
  engine      int,
  summary     text,
  started_at  timestamptz not null default now(),
  ended_at    timestamptz
);
alter table public.task_history enable row level security;

create policy "own rows: select" on public.task_history for select using (auth.uid() = user_id);
create policy "own rows: insert" on public.task_history for insert with check (auth.uid() = user_id);
create policy "own rows: update" on public.task_history for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows: delete" on public.task_history for delete using (auth.uid() = user_id);
-- Never store provider API keys, passwords, OTPs or screen contents in these tables.
