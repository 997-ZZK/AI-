create extension if not exists vector;

create table documents (
    id uuid primary key,
    kb_id varchar(64) not null,
    title varchar(300) not null,
    source_type varchar(32) not null,
    source_uri varchar(1000),
    content_hash varchar(64) not null,
    status varchar(32) not null default 'PENDING',
    chunk_count integer not null default 0,
    error text,
    created_by uuid,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_documents_kb_hash unique (kb_id, content_hash)
);
create index idx_documents_kb on documents (kb_id, created_at desc);

create table document_chunks (
    id uuid primary key,
    document_id uuid not null references documents (id) on delete cascade,
    kb_id varchar(64) not null,
    chunk_index integer not null,
    section_path varchar(500),
    page_from integer,
    page_to integer,
    content text not null,
    char_count integer not null,
    content_hash varchar(64) not null,
    embedding vector(1024),
    created_at timestamptz not null
);
create index idx_chunks_doc on document_chunks (document_id, chunk_index);
create index idx_chunks_kb on document_chunks (kb_id);
create index idx_chunks_embedding on document_chunks using hnsw (embedding vector_cosine_ops);
