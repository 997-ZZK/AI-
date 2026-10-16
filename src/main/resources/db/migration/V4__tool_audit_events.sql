-- 工具调用审计落库：内存环形缓冲解决「此刻能查」，这张表解决「跨重启追溯」。
--
-- 四个刻意的选择：
--   1) 不建外键：审计要能在用户/会话被删之后仍然回答「谁在什么时候用了什么」。
--      外键会把审计记录跟着被审计对象一起带走 —— 合规留痕最不该依赖被审计对象的存活。
--   2) detail 只存 240 字以内的摘要、args_hash 只存指纹：工具参数里常有手机号、token，
--      审计要能定位「是不是同一个调用」，但不该变成第二个敏感数据仓库。
--   3) 索引按三个查询视角建：时间（默认倒序）、工具（这个工具最近被谁用）、用户（这个人最近用了什么）。
--      审计写入远多于读取，索引不宜再多 —— 每多一个索引都是写入路径上的成本。
--   4) 主键用 bigserial：纯追加、无跨库合并需求，自增单调就够，且天然按写入顺序分页。
create table tool_audit_events (
    id bigserial primary key,
    at timestamptz not null,
    outcome varchar(16) not null,
    user_id uuid,
    session_id uuid,
    run_id uuid,
    tool_id varchar(128) not null,
    kind varchar(16) not null,
    duration_ms bigint not null default 0,
    args_hash varchar(64),
    detail varchar(500),
    created_at timestamptz not null default now()
);

create index idx_tool_audit_at on tool_audit_events (at desc);
create index idx_tool_audit_tool_at on tool_audit_events (tool_id, at desc);
create index idx_tool_audit_user_at on tool_audit_events (user_id, at desc);

comment on table tool_audit_events is '工具调用审计；写入异步且失败不阻断调用，保留期见 aiflow.tools.audit.retention-days';
