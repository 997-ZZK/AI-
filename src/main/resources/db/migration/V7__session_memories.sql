-- 短期记忆的滚动摘要（设计见 docs/memory-design.md）。
--
-- 为什么不是「水位消息 id」：messages.id 是随机 UUID、没有单调性；created_at 只有微秒精度，
-- 同一微秒内的两条会并列。而 messages 是只追加表，order by created_at, id 是稳定全序 ——
-- 于是「最老的 N 条已经折进摘要」这个前缀计数是精确的，且**不需要动 messages 表**。
--
-- 为什么失败计数与错误进表：摘要是旁路维护，失败不许影响运行，但「一直失败」必须看得见 ——
-- 连续失败到 aiflow.memory.summary-max-attempts 就停用该会话的摘要，避免坏会话每轮烧一次模型调用。
--
-- 为什么 tokens 记在这：摘要调用的 token 计入用户额度（诚实记账），累计值放这里才能回答
-- 「这个会话的记忆花了多少钱」。
create table session_memories (
    session_id       uuid primary key references sessions (id) on delete cascade,
    summary          text,
    summarized_count integer not null default 0,
    summary_chars    integer not null default 0,
    model            varchar(64),
    tokens           integer not null default 0,
    attempts         integer not null default 0,
    last_error       varchar(400),
    updated_at       timestamptz not null
);
