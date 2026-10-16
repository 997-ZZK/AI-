create table users (
    id uuid primary key,
    external_id varchar(64) not null unique,
    display_name varchar(128),
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table workflows (
    id uuid primary key,
    name varchar(128) not null,
    version integer not null,
    definition jsonb not null,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_workflows_name_version unique (name, version)
);

create table sessions (
    id uuid primary key,
    user_id uuid not null references users (id),
    workflow_id uuid references workflows (id),
    title varchar(200),
    status varchar(32) not null default 'ACTIVE',
    last_active_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index idx_sessions_user on sessions (user_id, last_active_at desc);

create table messages (
    id uuid primary key,
    session_id uuid not null references sessions (id),
    user_id uuid not null,
    role varchar(16) not null,
    content_type varchar(16) not null default 'TEXT',
    content text,
    client_msg_id varchar(64),
    run_id uuid,
    created_at timestamptz not null
);
create index idx_messages_session on messages (session_id, created_at);
create unique index uk_messages_client on messages (session_id, client_msg_id) where client_msg_id is not null;

create table workflow_runs (
    id uuid primary key,
    workflow_id uuid not null,
    workflow_version integer not null,
    session_id uuid,
    user_id uuid,
    status varchar(32) not null,
    input jsonb,
    output jsonb,
    error text,
    steps integer not null default 0,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null
);
create index idx_runs_session on workflow_runs (session_id, created_at desc);
create index idx_runs_status on workflow_runs (status);

create table node_runs (
    id uuid primary key,
    run_id uuid not null references workflow_runs (id),
    node_id varchar(64) not null,
    node_type varchar(64) not null,
    status varchar(32) not null,
    attempt integer not null default 1,
    input jsonb,
    output jsonb,
    error text,
    started_at timestamptz,
    ended_at timestamptz
);
create index idx_node_runs_run on node_runs (run_id, started_at);
