-- 长期记忆：用户级的事实与偏好（设计见 docs/long-memory-design.md）。
--
-- 为什么作用域是 user 而不是 session：会话级记忆（V7 session_memories）回答「刚才聊了什么」；
-- 跨会话稳定成立的知识（「我们公司报销要附行程单」「回答别超过三行」）属于**用户**。
-- 换个会话就丢，用户感受到的是「这个助手记不住事」。
--
-- 为什么一行一个 (kind, mem_key) 而不是一段自由文本：要「可查、可改、可删」。整段摘要既没法单独删除，
-- 也没法只纠正其中一条。unique (user_id, kind, mem_key) 同时是 upsert 的落点与并发下的单赢家约束。
--
-- 为什么向量列不进 JPA 实体（与 document_chunks 同一口径）：向量由 pgvector 提供，读写全走原生 SQL
-- （见 PgVectorUserMemoryVectorStore）；H2 测试库不装扩展也能建表、跑用例。
--
-- 为什么事件表不设外键：删除语义是硬删 + 同事务清掉它的事件行（用户说「删掉」就该删干净）。
-- 有外键反而要求先删子表；memory_id 也不该被约束成「必须还存在」。
--
-- 为什么 embedding_model 单独一列：向量只在同一个模型的向量空间里可比。换了 embedding 链之后
-- 旧向量继续参与余弦会给出「看起来正常」的分数 —— 静默错误。召回只取与当前 provider 一致的行。

create table user_memories (
    id uuid primary key,
    user_id uuid not null references users (id),
    kind varchar(16) not null,
    mem_key varchar(128) not null,
    display_key varchar(200) not null,
    -- 列名不叫 value：H2 把 VALUE 当保留字（PG 不当），测试库与生产库用同一份实体映射时会在 H2 上炸
    mem_value text not null,
    confidence numeric(3, 2) not null default 1.00,
    source varchar(16) not null,
    source_session uuid,
    source_run uuid,
    evidence varchar(500),
    embedding vector(1024),
    embedding_model varchar(128),
    hits integer not null default 0,
    last_used_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_user_memories_key unique (user_id, kind, mem_key)
);
create index idx_user_memories_user on user_memories (user_id, updated_at desc);
create index idx_user_memories_embedding on user_memories using hnsw (embedding vector_cosine_ops);

create table user_memory_events (
    id uuid primary key,
    memory_id uuid not null,
    user_id uuid not null,
    action varchar(16) not null,
    mem_key varchar(128),
    actor varchar(64),
    run_id uuid,
    detail varchar(300),
    at timestamptz not null
);
create index idx_user_memory_events_user on user_memory_events (user_id, at desc);
create index idx_user_memory_events_memory on user_memory_events (memory_id);
