-- Durable execution segments, ordered across instances by their insertion sequence.
-- Admission and resume transactions lock the sessions row before allocating seq.
create table session_dispatch_tasks (
    id uuid primary key,
    session_id uuid not null references sessions (id),
    run_id uuid not null references workflow_runs (id),
    seq bigint generated always as identity unique,
    kind varchar(16) not null,
    checkpoint_id uuid references run_checkpoints (id),
    approval_id uuid references approvals (id),
    dedupe_key varchar(96) not null unique,
    status varchar(16) not null,
    owner_token varchar(36),
    lease_until timestamptz,
    cancel_requested_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    started_at timestamptz,
    finished_at timestamptz,
    last_error varchar(4000),
    constraint ck_dispatch_kind check (kind in ('PROCESS', 'RESUME')),
    constraint ck_dispatch_status check (status in ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELED')),
    constraint ck_dispatch_resume_source check (
        (kind = 'PROCESS' and checkpoint_id is null and approval_id is null)
        or (kind = 'RESUME' and checkpoint_id is not null and approval_id is not null)
    ),
    constraint ck_dispatch_running_lease check (
        status <> 'RUNNING' or (owner_token is not null and lease_until is not null and started_at is not null)
    )
);

create index idx_dispatch_session_unfinished on session_dispatch_tasks (session_id, seq)
    where status in ('QUEUED', 'RUNNING');
create index idx_dispatch_queue on session_dispatch_tasks (seq) where status = 'QUEUED';
create index idx_dispatch_expired on session_dispatch_tasks (lease_until) where status = 'RUNNING';
create index idx_dispatch_run on session_dispatch_tasks (run_id, seq);

comment on table session_dispatch_tasks is 'Durable PROCESS / RESUME execution segments; expired RUNNING is failed, never replayed';
comment on column session_dispatch_tasks.seq is 'Global sequence, ordered within the locked session admission transaction; gaps are allowed';
comment on column session_dispatch_tasks.cancel_requested_at is 'Durable cancellation request observed by the current worker';
comment on column session_dispatch_tasks.owner_token is 'Opaque ownership token for conditional renewal and transactional write fencing';
