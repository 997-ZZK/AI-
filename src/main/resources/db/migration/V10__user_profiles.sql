-- M4-3B: profile control state only. Historical runs, audit records and memories
-- remain their respective facts; reset only disables this view and advances its floor.
create table user_profiles (
    user_id uuid primary key references users (id),
    aggregation_enabled boolean not null default false,
    statistics_since timestamptz not null,
    timezone varchar(64) not null default 'UTC',
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_user_profiles_version check (version >= 0)
);
