# 数据模型（M1 + M2）

## 通用约定

- 主键：应用侧生成 `uuid`，不用自增（便于分片与离线导入）。
- 时间：`timestamptz`，应用侧一律 UTC `Instant`。
- 非结构化/半结构化数据：`jsonb`（Hibernate 侧 `@JdbcTypeCode(SqlTypes.JSON)` + `JsonNode`）。
- 状态字段：`varchar(32)` 存枚举名（`@Enumerated(EnumType.STRING)`），可读性优先。
- 迁移：Flyway `V{n}__{snake_desc}.sql`，**只增不改**；表结构变更同步更新本文件。

## 1. users

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | 用户 ID |
| external_id | varchar(64) UNIQUE NOT NULL | 外部身份（登录体系接入前的标识） |
| display_name | varchar(128) | 昵称 |
| status | varchar(32) NOT NULL | ACTIVE / DISABLED |
| created_at / updated_at | timestamptz NOT NULL | 创建/更新时间 |

## 2. workflows

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | 工作流 ID |
| name | varchar(128) NOT NULL | 逻辑名（同名校验按 session 解析最新版） |
| version | integer NOT NULL | 版本号，(name, version) 唯一 |
| definition | jsonb NOT NULL | `WorkflowDefinition` 完整定义（节点/边/重试/超时） |
| status | varchar(32) NOT NULL | ACTIVE / ARCHIVED |
| created_at / updated_at | timestamptz NOT NULL |  |

索引/约束：`uk_workflows_name_version (name, version)`。

## 3. sessions

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | 会话 ID（也是会话队列与锁的粒度） |
| user_id | uuid NOT NULL FK -> users | 归属用户 |
| workflow_id | uuid FK -> workflows | 可空；为空时用默认工作流 `basic-chat` |
| title | varchar(200) | 标题（M4 记忆/画像接入后自动生成） |
| status | varchar(32) NOT NULL | ACTIVE / ARCHIVED |
| last_active_at | timestamptz | 最近活跃（列表排序与归档依据） |
| created_at / updated_at | timestamptz NOT NULL |  |

索引：`idx_sessions_user (user_id, last_active_at desc)`。

## 4. messages

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | 消息 ID |
| session_id | uuid NOT NULL FK -> sessions |  |
| user_id | uuid NOT NULL | 冗余自 session，便于按用户查询 |
| role | varchar(16) NOT NULL | USER / ASSISTANT / SYSTEM |
| content_type | varchar(16) NOT NULL | TEXT / IMAGE / AUDIO（M3-M4 扩展） |
| content | text | 文本内容（长内容/M4 附件走对象存储） |
| client_msg_id | varchar(64) | 幂等键（客户端生成） |
| run_id | uuid | 关联的运行（USER 消息指向其触发的 run；ASSISTANT 消息指向产出它的 run） |
| created_at | timestamptz NOT NULL |  |

索引：`idx_messages_session (session_id, created_at)`；`uk_messages_client (session_id, client_msg_id) WHERE client_msg_id IS NOT NULL`（部分唯一索引，兜底幂等）。

## 5. workflow_runs

运行主表，一行一次执行。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | runId（创建时即返回给客户端，用于订阅 SSE） |
| workflow_id | uuid NOT NULL | 冗余存储（不设 FK，工作流可归档） |
| workflow_version | integer NOT NULL | 执行时的定义版本（回放/审计关键） |
| session_id | uuid |  |
| user_id | uuid |  |
| status | varchar(32) NOT NULL | PENDING / RUNNING / WAITING_APPROVAL / SUCCEEDED / DEGRADED / FAILED / CANCELED |
| input / output | jsonb | 运行输入、最终输出（含 fallback） |
| error | text | 失败原因（人类可读） |
| steps | integer NOT NULL | 实际执行步数（步数预算审计） |
| started_at / finished_at | timestamptz | 挂起中的运行 `finished_at` 为空 |
| waiting_node_id | varchar(64) | V6：挂起在哪个节点（非空即等人工审批）；恢复成功 / 取消 / 收尾都会清掉 |
| created_at | timestamptz NOT NULL |  |

索引：`idx_runs_session (session_id, created_at desc)`、`idx_runs_status (status)`。

## 6. node_runs

节点级快照，**每次尝试一行**，是回放、评测、计费的数据基础。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK |  |
| run_id | uuid NOT NULL FK -> workflow_runs |  |
| node_id | varchar(64) NOT NULL | 定义中的节点 ID |
| node_type | varchar(64) NOT NULL | 如 `llm.chat` |
| status | varchar(32) NOT NULL | RUNNING / WAITING / SUCCEEDED / DEGRADED / FAILED / SKIPPED |
| attempt | integer NOT NULL | 第几次尝试（从 1 开始） |
| input / output | jsonb | 节点入参变量快照、输出 |
| error | text | 失败/降级原因 |
| started_at / ended_at | timestamptz |  |

索引：`idx_node_runs_run (run_id, started_at)`。

## 7. documents

知识库文档主表（M2）。`kb_id` 做知识库隔离；同库内 `content_hash` 唯一，用于重复上传检测。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | 文档 ID |
| kb_id | varchar(64) NOT NULL | 知识库标识（应用侧约定，如 demo / product-a） |
| title | varchar(300) NOT NULL | 标题（展示与引用） |
| source_type | varchar(32) NOT NULL | TEXT / MARKDOWN / JSON（PDF / IMAGE 归 M3） |
| source_uri | text | 来源地址或文件名（可空） |
| content_hash | varchar(64) NOT NULL | 原文 SHA-256 |
| status | varchar(32) NOT NULL | PENDING / INDEXED / FAILED |
| chunk_count | integer NOT NULL DEFAULT 0 | 切片数 |
| error | text | 失败原因（人类可读） |
| created_by | uuid | 上传用户（可空） |
| object_key | varchar(512) | V5：对象存储里的原件键；**null 是常态**（留存未开启，或留存失败——失败会记 WARN，响应里直接看得到） |
| object_bytes | bigint | V5：原件字节数 |
| created_at / updated_at | timestamptz NOT NULL |  |

索引/约束：`uk_documents_kb_hash (kb_id, content_hash)`、`idx_documents_kb (kb_id, created_at desc)`。

原件键形如 `kb/{kbId}/documents/{documentId}/{fileName}`：按 kb / document 分层便于按前缀批量清理，
文件名做一次净化（路径分隔符换成下划线），避免把上层目录语义写进键里。
二进制**不进数据库**：原件动辄几 MB，塞进 PG 会把备份、复制、连接池一起拖慢，而它的访问模式（低频、整份读、按 key 取）
正好是对象存储的形状。

## 8. document_chunks

切片表（M2）。`embedding` 由 pgvector 提供，写入与查询走原生 SQL（`cast(? as vector)`）；Hibernate 不映射该列，测试库（H2）无需安装扩展。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | chunk_id，也是提示词 `[n]` 的溯源目标 |
| document_id | uuid NOT NULL FK -> documents | `on delete cascade`（删文档即删向量） |
| kb_id | varchar(64) NOT NULL | 冗余自文档，检索过滤用 |
| chunk_index | integer NOT NULL | 文档内顺序（相邻合并与顺序还原） |
| section_path | varchar(500) | 标题路径，如「员工手册 > 报销 > 差旅」 |
| page_from / page_to | integer | 页码（PDF 用，纯文本可空） |
| content | text NOT NULL | 切片正文 |
| char_count | integer NOT NULL | 字数（token 预算估算） |
| content_hash | varchar(64) NOT NULL | 切片 SHA-256（融合去重） |
| embedding | vector(1024) | 1024 维向量；H2 测试库无此列 |
| embedding_model | varchar(128) | V3：向量由哪个 embedding provider 生成；同一次 SQL 写入；升级前的历史行为 null（按 `(unknown)` 处理，不参与检索） |
| created_at | timestamptz NOT NULL |  |

索引：`idx_chunks_doc (document_id, chunk_index)`、`idx_chunks_kb (kb_id)`、`idx_chunks_kb_model (kb_id, embedding_model)`、`idx_chunks_embedding USING hnsw (embedding vector_cosine_ops)`。

## 9. tool_audit_events

工具调用审计（V4 新增）。内存环形缓冲解决"此刻能查"，这张表解决"跨重启追溯"。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | bigserial PK | 纯追加；自增单调，天然就是写入顺序 |
| at | timestamptz NOT NULL | 发生时刻（应用时钟） |
| outcome | varchar(16) NOT NULL | SUCCESS / REPLAYED / FAILED / DENIED |
| user_id / session_id / run_id | uuid | 主体视角的三个维度；**刻意不建外键** —— 审计要能在用户/会话被删之后仍然回答"谁用过什么" |
| tool_id | varchar(128) NOT NULL | 工具 id |
| kind | varchar(16) NOT NULL | HTTP / MCP / SKILL |
| duration_ms | bigint NOT NULL | 未真正调用（被策略拒绝）时为 0 |
| args_hash | varchar(64) | 参数指纹（sha256 前 16 位）：只记指纹不记原文，避免审计变成第二个敏感数据仓库 |
| detail | varchar(500) | 结果摘要（写入前已截断到 240 字） |
| created_at | timestamptz NOT NULL | 落库时刻；与 `at` 的差即写库延迟，异步批量写的健康度指标 |

索引：`idx_tool_audit_at (at desc)`、`idx_tool_audit_tool_at (tool_id, at desc)`、`idx_tool_audit_user_at (user_id, at desc)`。

写入路径：`ToolAuditService.record` -> `ToolAuditPersistence`（单线程、64 条一批、队列满丢最老的、写失败只计数），
工具调用路径上只多一次入队。查询优先读库（`Specification` 拼非空条件，不用 `? is null` 那种在 PostgreSQL 上
推断不出参数类型的 JPQL），库不可用时回退内存缓冲并在响应里把 `store` 标成 `memory`。
保留期由 `aiflow.tools.audit.retention-days`（默认 30 天）决定，清理在写入线程里顺带做，不引入新调度器。

## 10. approvals（V6）

人工审批待办。一行 = 一层审批（升级会产生新行，旧行留 EXPIRED），**策略在请求时刻快照进 `policy`** ——
超时该放行还是打回、能不能升级、升级给谁，都按当时的配置算，之后改工作流不影响已经挂起的单子。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | 决策地址用 approvalId 而不是 run+node：升级后 run+node 不再唯一 |
| run_id | uuid NOT NULL FK -> workflow_runs |  |
| node_id | varchar(64) NOT NULL | 审批节点 id |
| level | integer NOT NULL DEFAULT 1 | 第几层（升级 +1） |
| status | varchar(24) NOT NULL | PENDING / APPROVED / REJECTED / EXPIRED / CANCELED |
| title / detail | varchar(2000) / varchar(8000) | 展示给审批人的标题与详情（模板已渲染） |
| assignees | varchar(512) NOT NULL | 审批人名单（CSV）；含 `any` 表示不限 |
| policy | jsonb NOT NULL | 策略快照：onTimeout / onExhausted / maxLevel / escalateAssignees / onReject / fallback / waitSeconds |
| decision | varchar(24) | APPROVE / REJECT（人工与超时都会写） |
| decided_by | varchar(128) | 决策人；超时收口与取消记 `system` |
| decided_at / comment | timestamptz / text | 决策时间与意见 |
| due_at | timestamptz NOT NULL | 决策窗口截止（超时扫描按它挑） |
| created_at / updated_at | timestamptz NOT NULL |  |

索引/约束：`uq_approvals_level (run_id, node_id, level)` 唯一（重试落重单是第二道闸）、`idx_approvals_due (status, due_at)`、
`idx_approvals_run (run_id, created_at)`。三处并发（人工决策 / 超时扫描 / 随运行取消）都走 `where status = PENDING` 条件更新决出单赢家 ——
「先查后写」在单实例上没问题，多实例下会双双成功，那样一次审批会触发两次恢复。

## 11. approval_events（V6）

审批流水的追加型表（谁、何时、做了什么）：REQUESTED / APPROVED / REJECTED / EXPIRED / CANCELED，
`detail` 里放该事件特有的字段（level、toLevel、action、via=timeout 等）。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint identity PK | 纯追加，自增即顺序 |
| approval_id | uuid NOT NULL FK -> approvals | `on delete cascade` |
| event | varchar(32) NOT NULL | 见上 |
| actor | varchar(128) | 人 / `system` |
| comment | varchar(2000) | 备注 |
| detail | jsonb | 事件字段（level、toLevel、action、via=timeout 等） |
| created_at | timestamptz NOT NULL |  |

索引：`idx_approval_events_approval (approval_id, created_at)`。

## 12. run_checkpoints（V6）

挂起运行的续跑状态。**一行一次挂起**（`seq` 递增、`consumed_at` 标记已消费），恢复时读最新未消费的一条，
恢复完成立即消费；解析失败直接抛异常（不静默当"没有检查点"，那会让一个挂起的运行被误判为无法恢复）。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK |  |
| run_id | uuid NOT NULL FK -> workflow_runs | `on delete cascade` |
| node_id | varchar(64) NOT NULL | 挂起节点 |
| seq | integer NOT NULL | 同一运行的第几次挂起 |
| state | jsonb NOT NULL | RunCheckpoint：nodeId / nodeRunId / nextIndex（挂起节点下标 +1）/ activated / variables / steps / workflowVersion |
| created_at | timestamptz NOT NULL |  |
| consumed_at | timestamptz | 非空 = 已恢复过（不再生效） |

索引：`idx_run_checkpoints_run (run_id, seq)`。检查点**不存审批结果**：决策在 `approvals` 行里，
恢复时按 id 重读那一行拿快照策略，避免同一事实存两份、改一份忘一份。

## 13. session_memories（V7）

一行 = 一个会话的滚动摘要（不存在 = 还没摘要）。**不存消息副本**：摘要覆盖的是 `messages` 的**前缀**，
原消息仍在 `messages` 里 —— 摘要是「可重算的派生数据」，不是第二份事实来源（这也是 `rebuild` 敢直接清的原因）。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| session_id | uuid PK FK -> sessions | 与会话一对一 |
| summary | text | 摘要正文（null = 还没折过） |
| summarized_count | integer NOT NULL DEFAULT 0 | 已折进摘要的**前缀条数**：比存「水位消息 id」便宜，且消息被清理后仍自洽 |
| model | varchar(128) | 产出摘要的模型（换模型重算后要能看出来） |
| tokens | integer NOT NULL DEFAULT 0 | 累计花掉的 token（计入用户额度；`rebuild` **不**重置） |
| attempts / last_error | integer NOT NULL DEFAULT 0 / varchar(400) | 失败记账；连续失败到 `summary.max-attempts` 停用该会话 |
| created_at / updated_at | timestamptz NOT NULL | `updated_at` 只在真正推进时前进 |

并发：折叠用 `where summarized_count = :expected` 条件更新决出单赢家（多实例同时折叠只有一个生效，与审批决策同一手法）；
`reset` 清 `summary` + `summarized_count` 但保留 `tokens`。

## 14. user_memories / user_memory_events（V8）

长期记忆（设计见 `docs/long-memory-design.md`）：**用户级**的事实与偏好，跨会话召回。

### user_memories

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | 应用侧生成 |
| user_id | uuid NOT NULL FK -> users | 作用域是用户，与会话无关 |
| kind | varchar(16) NOT NULL | `FACT`（客观事实）或 `PREFERENCE`（对回答方式的要求） |
| mem_key | varchar(128) NOT NULL | 归一化后的槽位键（去空白 / 剥人称前缀 / 小写化） |
| display_key | varchar(200) NOT NULL | 给人看的原始 key |
| mem_value | text NOT NULL | 正文（**列名不叫 value**：H2 把 `VALUE` 当保留字，那样写 H2 单测跑不起来） |
| confidence | numeric(3,2) NOT NULL DEFAULT 1.00 | 抽取给的置信度；手工录入恒 1.00 |
| source | varchar(16) NOT NULL | `MANUAL` / `EXTRACTED`（`MANUAL` 永不被抽取覆盖） |
| source_session / source_run | uuid NULL | 这条是从哪一段对话抽出来的（手工录入为空） |
| evidence | varchar(500) NULL | 原文片段（排障与人工核对用） |
| embedding | vector(1024) NULL | 与 `document_chunks` 同口径；维度固定，改维度要同时改迁移与配置 |
| embedding_model | varchar(128) NULL | 向量来源；与当前链不一致的条目不参与余弦（诊断记 `stale-embeddings:<model>`） |
| hits | integer NOT NULL DEFAULT 0 | 被召回次数（**预览不回写**，只有真实装配回写） |
| last_used_at | timestamptz NULL | 最近一次被召回的时间 |
| created_at / updated_at | timestamptz NOT NULL | 归并覆盖时 `updated_at` 前进 |

约束与索引：`unique (user_id, kind, mem_key)` 保证同槽位单行（人工优先的归并靠它 + 条件更新单赢家）；
`idx_user_memories_user (user_id, updated_at desc)` 给列表；`idx_user_memories_embedding` 是 HNSW（`vector_cosine_ops`）。

### user_memory_events

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK | |
| memory_id | uuid NULL FK -> user_memories ON DELETE CASCADE | 被拒的新增没有对应行，所以可空 |
| user_id | uuid NOT NULL FK -> users | |
| action | varchar(16) NOT NULL | `CREATED` / `UPDATED` / `REJECTED`；**不写 `DELETED`** —— 硬删会在同事务里连带清掉事件行（见下） |
| mem_key | varchar(128) NOT NULL | 事件发生时的槽位键（行被删后仍能追溯发生过什么） |
| actor | varchar(64) NOT NULL | `USER`（手工）或 `SYSTEM`（抽取） |
| source_run | uuid NULL | 触发这次事件的那次运行 |
| detail | varchar(400) NULL | 原因（例如 `manual-entry` / `over-limit` / `below-threshold`） |
| at | timestamptz NOT NULL | |

**事件里不写正文**：`detail` 只记原因与计数，正文只存在 `user_memories.mem_value` 一处（少一处泄漏面，也多一个删除点要清）。

删除语义：**硬删**条目并在**同事务里清掉它的事件行** —— 用户在控制台点一次删除，不该留下可回溯的正文片段。

## 与引擎状态机的对应

### M4-3A V9：session_dispatch_tasks（已批准，已实现并完成核心验收）

任务表含 id(UUID PK)、session_id/run_id(FK)、seq(BIGINT identity UNIQUE)、kind(PROCESS/RESUME)、checkpoint_id/approval_id、dedupe_key(UNIQUE)、
status(QUEUED/RUNNING/DONE/FAILED/CANCELED)、owner_token/lease_until、cancel_requested_at、created_at/started_at/finished_at 与 last_error。
同会话入队通过短事务锁 sessions 行序列化，领取只选最早未完成任务。租约和取消状态跨实例持久化，不给 workflow_runs 新增列。
任务不存输入正文；PROCESS 从 run.input 读，RESUME 从批准记录和检查点恢复。schema 本阶段只增加 V9，不修改既有 V1-V8。
任务表没有自动删除生命周期；已有产物不清理。Stream 是有限回放数据，2000 条/24h 保留由 Redis 配置实现，不另建 run_events 表。

- `RunStatus.PENDING` 在接入消息时就落库（客户端可立即拿 runId 订阅），进入引擎后转 RUNNING。
- 节点每次尝试先在 `node_runs` 落 RUNNING 再执行，结束后更新同一行；重试产生新行（attempt 递增）。
- 运行的输出优先级：`end` 节点输出映射 `__output__` -> 兜底为过滤内部变量（`__` 前缀）后的变量表。

## 后续里程碑的预留（不提前建表）

M4-3D D2 不改 schema：`workflow_runs` / `node_runs` / 检查点 JSON 仅保存文本、UUID 音频句柄和受控元数据，不保存音频 byte[]、base64 或音频 DTO。句柄在当前进程/执行段内有效，终态或挂起即释放；持久快照不构成音频存储，也不支持重启或跨实例恢复音频。

- M4 后续：`run_events`（Redis Stream 之外的可选冷备）；`user_profiles` 已在 M4-3B V10 交付。
  （长期记忆的两张表已在 V8 落地，见 §14 —— 预留清单不再列它。）
- M4-3B 已实现并验收，契约见 `docs/profile-design.md`：V10 `user_profiles` 仅存画像控制状态，统计实时聚合，偏好真值复用 `user_memories`。真实 PostgreSQL Flyway 迁移成功，证据见 `docs/m4-3b-validation.md`。
  V9 已由本阶段迁移执行；Redis Stream 不另建 PostgreSQL `run_events` 表。
- M4：`sessions`/`messages` 增加归档与保留策略字段（`archived_at` 等）。

## 演进规则

### M4-3B V10：user_profiles（已实现并完成本轮验收）

`user_id` UUID PK/FK→users；`aggregation_enabled` BOOLEAN NOT NULL DEFAULT false；`statistics_since` TIMESTAMPTZ NOT NULL；
`timezone` VARCHAR(64) NOT NULL DEFAULT 'UTC'；`version` BIGINT NOT NULL DEFAULT 0；`created_at/updated_at` TIMESTAMPTZ NOT NULL。
无偏好正文、消息副本或统计快照。无行等同关闭、UTC、version=0；GET不建行。写操作锁用户行并校验expectedVersion，成功修订递增版本。
DELETE只停用和更新起点，不删除此表、运行、审计或长期记忆；重新启用从新的时间起算。最近30天统计不修改源记录。

1. 新增列一律可空或带默认值；破坏性变更必须新表 + 数据迁移脚本 + 文档记录。
2. 任何迁移必须先在本地 `docker compose up -d` 环境跑通，再提交。
3. 索引变更同样走迁移；查询慢先看 `EXPLAIN` 再决定加索引。
