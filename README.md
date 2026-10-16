# AIFlowEngine

面向「AI 干活」的 LLM 工作流引擎（mini-Dify 方向）：DAG 编排、RAG、模型分级路由、会话与记忆、多模态接入。

- 设计决策：`docs/architecture.md`
- 里程碑与验收：`docs/roadmap.md`
- 数据模型：`docs/data-model.md`
- 质量方案（压测/评测/故障注入）：`docs/quality-plan.md`
- 当前阶段：**M4-3A/B、D0/D1/D2 mock 音频、第一层协议抽象、第二层离线黄金集、本地使用与运维入口、运行回放与失败定位已验收；C 保留阶段性压测结论**。当前运行手册见 [local-ops-runbook.md](docs/local-ops-runbook.md)，回放验收见 [run-replay-validation.md](docs/run-replay-validation.md)。
- 对话 HTTP 错误边界已补齐：上游错误正文和原始异常链不再进入调用诊断，非 2xx 只保留状态码；正常回答和流式降级规则延续既有契约。本轮全量 1019 条、0 失败/错误、6 跳过，见 [验收记录](docs/chat-error-validation.md)。
- Embedding 与探活错误边界已验收：HTTP 错误、网络/转换/构造异常使用固定诊断，缺失模型不再回显上游清单；向量与探活判定未改。定向 66 条、全量 1054 条（0 失败/错误、6 跳过）及离线打包通过，见 [验收记录](docs/embedding-probe-error-validation.md)。

## 快速开始

已有 JDK 21、交付 JAR、V10 PostgreSQL 和 Redis 时，可以直接使用强制 mock 的本机入口，无需模型账号或额度：

```powershell
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\offline-engine.ps1 -Action Check
$demoMarker = 'offline-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\offline-engine.ps1 -Action Start -Marker $demoMarker
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\verify-offline-demo.ps1 -Marker $demoMarker
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\offline-engine.ps1 -Action Stop -Marker $demoMarker
```

默认监听 `127.0.0.1:8083`，只使用既有本机设施，不自动迁移；演示会新增合成记录并保留报告。可将 `powershell` 换为 `pwsh`。参数、Status、诊断和固定关闭项见 [运行手册](docs/local-ops-runbook.md)。下面是常规构建/开发入口，其配置与强制 mock 入口分别管理。

前置：JDK 21、Maven 3.9+、Docker。

本机工具链已安装在 `D:\tools`（仅当前会话生效，不改系统环境变量）：

```powershell
. .\script\dev-env.ps1                              # 激活 JDK 21 + Maven
mvn -s script\maven-settings.xml test                # 阿里云镜像加速依赖下载
```

> PowerShell 里传 `-D` 参数请加单引号（`'-Dmaven.repo.local=...'`），不加引号可能被拆成两个参数导致属性丢失。

```powershell
# 先在当前 shell 注入本机开发凭证（不要写入仓库或日志）
$env:POSTGRES_PASSWORD = '<local-postgres-password>'
$env:MINIO_ROOT_PASSWORD = '<local-minio-password>'
$env:DB_PASSWORD = $env:POSTGRES_PASSWORD
docker compose up -d          # PostgreSQL 15432 / Redis 16379（刻意避开同机其他项目端口）
mvn spring-boot:run
```

浏览器打开 `http://localhost:8080/console.html`：控制台可以建用户/会话（选择 `basic-chat` / `rag-chat` / `vision-chat` / `approval-chat`）、上传文档或粘贴文本入库、做检索调试（看每个切片的分数与引用）、发消息并用 SSE 看节点事件与最终引用；第 4 节是人工审批看板（待办列表、批准/拒绝、审批链与事件流、通知渠道水位与计数）—— 跑 `approval-chat` 时 SSE 会停在 `RUN_WAITING`，在那里决策后运行继续；顶部有「令牌」输入框与「我是谁」自检（开了鉴权后审批以令牌身份为准，匿名点批准会明确报 401）。

画像面板可查询行为统计、启用/停用、修改时区、查看手工偏好决策及逻辑重置。画像默认关闭；重置保留运行、审计和长期记忆，切换用户会清空旧视图。

第 3.2 节“运行回放”可输入历史 runId，查看整体状态、每次节点尝试、重试失败和按需展开的输入/输出/错误。发送消息会回填最近 runId；支持分页与手动刷新。数据来自 PostgreSQL 已存快照，不重新执行工作流，不依赖 Redis 事件仍在保留期。接口为 `GET /api/v1/runs/{runId}/nodes?page=0&size=50` 与 `.../nodes/{nodeRunId}`，完整边界见 [回放方案](docs/run-replay-plan.md)。本轮全量 998 条、0 失败/错误、6 跳过，真实 API 23/23、原演示 43/43、浏览器 22 项通过。

首次启动会自动注册示例工作流 `basic-chat` 与 `rag-chat`（后者查 `kbId=demo`）。

健康检查：`http://localhost:8080/actuator/health`

## curl 快速验证

对话（M1 链路）：

```bash
curl -s -X POST localhost:8080/api/v1/users -H "Content-Type: application/json" \
  -d "{\"externalId\":\"demo\",\"displayName\":\"Demo\"}"

curl -s -X POST localhost:8080/api/v1/sessions -H "Content-Type: application/json" \
  -d "{\"userId\":\"<上一步返回的 id>\"}"

curl -s -X POST localhost:8080/api/v1/sessions/<会话id>/messages -H "Content-Type: application/json" \
  -d "{\"clientMsgId\":\"msg-1\",\"content\":\"你好，做个自我介绍\"}"

curl -N localhost:8080/api/v1/runs/<runId>/events -H "Accept: text/event-stream"
```

RAG（M2 链路）：入库 -> 检索调试 -> 带引用的问答。

```bash
# 1) 入库（text / markdown / json；PDF 与图片 OCR 在 M3）
curl -s -X POST localhost:8080/api/v1/kbs/demo/documents -H "Content-Type: application/json" \
  -d "{\"title\":\"报销制度.md\",\"sourceType\":\"MARKDOWN\",\"content\":\"# 报销制度\n\n## 发票要求\n发票抬头必须为公司全称，并注明税号。\"}"

# 2) 上传文件（multipart）
curl -s -X POST localhost:8080/api/v1/kbs/demo/documents/upload -F "file=@docs/报销制度.md"

# 3) 看文档与切片
curl -s localhost:8080/api/v1/kbs/demo/documents
curl -s localhost:8080/api/v1/kbs/demo/documents/<documentId>/chunks

# 4) 检索调试：看召回切片、分数、来源与各阶段统计（includeContext=true 时返回组装好的提示词块）
curl -s -X POST localhost:8080/api/v1/kbs/demo/retrieve -H "Content-Type: application/json" \
  -d "{\"query\":\"发票抬头要注明什么？\",\"topK\":5,\"minScore\":0.15,\"includeContext\":true}"

# 5) 会话绑定 rag-chat 后提问，终态事件里带 citations
curl -s -X POST localhost:8080/api/v1/sessions -H "Content-Type: application/json" \
  -d "{\"userId\":\"<userId>\",\"workflowName\":\"rag-chat\"}"
curl -s -X POST localhost:8080/api/v1/sessions/<会话id>/messages -H "Content-Type: application/json" \
  -d "{\"clientMsgId\":\"rag-1\",\"content\":\"发票抬头要注明什么？\"}"
```

## 当前能力

M1 引擎核心：

- 工作流：DAG 校验（重复/未知类型/自环/环/不可达）、条件边（SpEL）、节点重试、超时、步数预算、取消
- 执行：拓扑执行、事件总线（单调 seq + 回放）、SSE（Last-Event-ID 续传、心跳）
- 内置节点：`start`、`llm.chat`、`delay`、`end`；Mock 模型 provider + 路由接口
- 会话：按会话串行队列（同会话 FIFO、跨会话并行）、会话锁接口（Redis 实现可开关）、`clientMsgId` 幂等
- 持久化：Flyway V1（6 张表）、run/node_run 两级快照（回放基础）
- 运维：统一错误码、traceId 贯穿日志、Actuator + Prometheus；静态资源 404 与业务错误分流（路径写错不再被兜底 handler 吃成 500 + ERROR 栈）；**调用方写错的请求一律 4xx**（非法 JSON / 路径变量类型不对 400、方法不对 405、Content-Type 不对 415），且**不回显报文内容** —— Jackson 的原始 message 里带请求体前几十个字符，可能是用户提示词或备注；SSE 客户端断流（关页面 / 断网 / kill 客户端）走 debug —— 心跳 flush 失败与异步错误回调两条路径都不再把正常断连写成 ERROR 栈

M2 RAG 全链路：

- 文档：解析（text / markdown / json）-> 结构感知切片（目标 400 字、重叠 80 字、标题路径、contentHash）-> 1024 维向量 -> `documents` / `document_chunks`（pgvector 列 + HNSW，V2 迁移）
- 检索：`RetrievalProvider`（local / cloud 同接口）-> 分数归一化 -> 阈值过滤 -> 断崖 top-k -> MMR 去冗余 -> 相邻切片合并；单来源失败只告警
- 索引一致性：`embedding_model` 记录向量来源，检索按模型隔离；索引与当前 provider 不一致时显式失败（不是静默返回空结果），`POST /api/v1/kbs/{kbId}/reindex` 一键重建
- 提示词：`[n]` 编号资料块 + 引用要求 + 防注入声明（资料内容按不可信数据处理）
- 节点与工作流：`retrieval.search`（输出 `context` / `chunks` / `citations` / `retrievalStats`）+ 示例 `rag-chat`
- API：文档入库（JSON / multipart）、文档与切片列表、检索调试、会话绑定 `workflowName`
- 评测：9 篇 / 66 条黄金集（可答 55 + 不可答 11），门槛 recall@5 >= 0.85、MRR >= 0.70、拒答 >= 0.80，实测 0.923 / 0.918 / 0.909（mock embedding 校准点）
- 真实链路已实测：`docker compose` 上 PostgreSQL 16.15 + pgvector 0.8.6，Flyway V1/V2 应用成功；入库 -> pgvector 检索 -> 控制台 SSE 问答整条链路跑通（`vector_dims(embedding)=1024`、会话绑定 `rag-chat`、四节点快照 SUCCEEDED、终态带 `citations`）；V3 起 `document_chunks.embedding_model` 绑定向量来源，换 provider 后旧向量会被拒绝检索并提示重建（实测：迁移后旧向量按 `(unknown)` 处理 -> 503 带重建命令 -> `POST /reindex` -> 检索恢复）

M3 分级路由（进行中）：

- 档位：`order` 驱动的分级路由（默认 cloud -> local -> mock，质量优先、本地兜底），调用失败自动退下一档；`OpenAiCompatibleChatProvider` 覆盖本地 Ollama 与云端网关（SSE 增量、错误转可读异常、model 可覆盖）
- 健康：探活（每 15s `GET /models`，并核对配置的模型名在不在清单里）+ 连续失败熔断 + 冷却自动放行；探活判死的档位跳过不试并在快照留名（`skippedTiers`）
- 可见性：`GET /api/v1/capabilities` 给出档位矩阵（L0 全云 / L1 本地全功能 / L2 本地 + 纯文本 / L3 预设话术）、每个候选档位的可用性与原因、以及用户可读的降级提示
- 向量化分级链：`embedding.tiers` 多档兜底，启动期强制各档同 model（不同模型的向量不在同一空间）
- 多模态：`llm.vision` 节点（能力门槛 chat + vision）+ `POST /api/v1/attachments` 图片上传（文件头嗅探定类型、UUID 落盘、日志与快照不留 base64）；示例工作流 `vision-chat`，控制台可直接传图提问
- 能力矩阵新增 `vision` 通道：没有真实多模态档位时由 mock 承接，并在 `notice` 里明说「只回图片摘要」
- 工具调用：`tool.call` 节点（显式开关 + host/端口白名单 + SSRF 四道校验 + 幂等键 + 重复指纹判循环 + 运行级次数上限），3xx 一律拒绝跟随重定向
- 工具来源三种：HTTP（配置声明）/ MCP（自研客户端，stdio / Streamable HTTP / 老式 SSE 双端点三种传输，可从本机 Codex、Claude 配置导入，远程服务端支持静态 Bearer、`${VAR}` 占位与 OAuth 动态令牌）/ 本机技能库（`skill.list` + `skill.read`）；`GET /api/v1/tools` 一次看全白名单与失败原因，`POST /api/v1/tools/reload` 重新发现外部来源（细节见下文「MCP 服务端与本机技能库」）
- 内容安全：输入侧拦在入库与排队之前（400 + 命中类别），输出侧拦在返回之前（换预设话术 + `DEGRADED` + `blockCategory`）；流式增量审核用保留窗口兜住「已推送的 SSE 片段」，窗口默认按最长敏感词自动抬高，整词命中不会先推送再拦截（上限 `max-auto-window` 挡住病态超长词条）；词表外置、正则兜住提示词注入
- 每用户 token 额度：自然日口径、软阈值优先便宜档位、硬上限给预设话术、`GET /api/v1/budget/users/{userId}` 可查；计数可选 memory / redis
- 人工审批（原 M5，提前交付）：`approval` 节点落待办后**挂起运行**（检查点入库、节点行 WAITING、立刻释放会话锁与 worker），决策或超时后从断点恢复（不重放、恢复前校验版本、步数跨挂起累计）；接口 `GET /api/v1/approvals`、`POST /api/v1/approvals/{id}/decision`、`GET /api/v1/runs/{runId}/approvals`、`POST /api/v1/runs/{runId}/cancel?actor=`、`GET /api/v1/approvals/notify`（通知渠道状态）；超时按策略放行/打回/升级，状态变化异步投递到 `log` / `webhook` 渠道。细节与「这不是安全边界」的说明见下文
- 实测（本机未装本地模型）：`level=L3`、`chat=degraded`、`notice` 指明由 mock 承接；RAG 入库 -> pgvector 检索 -> 对话仍全链路可用

M4 短期记忆（2026-09-23 交付，M4 进行中）：

- 会话窗口：`llm.chat` / `llm.vision` 在装配期自动带上最近 N 条历史（默认 8 条 / 4000 字符，从最新往回留，超预算丢最老的）；节点参数 `history`（`inherit` / `none` / 数字）与 `includeSummary` 可逐节点覆盖
- 滚动摘要：攒够 `chunk` 条（默认 6）旧消息后在**运行收口之后异步**折进摘要（不占会话队列、失败不影响运行、**原消息不删**），以 `system` 消息置顶注入；摘要 token 计入用户额度
- 接口：`GET /api/v1/sessions/{id}/memory`（状态）/ `GET .../memory/preview?input=`（**把这一轮的装配结果摊开**：摘要 + 窗口 + 本轮输入，逐条给 role/chars）/ `POST .../memory/rebuild`（清摘要与计数、保留 token 累计）
- 默认值：窗口注入**开**（只读库）；滚动摘要**关**（要调模型花钱，与 OCR 同口径），`AIFLOW_MEMORY_SUMMARY_ENABLED=true` 打开
- 控制台第 3 节有「记忆状态 / 装配预览 / 重建摘要」三个按钮（浏览器手验通过，零 JS 报错）
- 实测：全量 `mvn test` 460 条 0 失败；真实栈 `script/verify-memory-flow.ps1` 20/20 PASS

M4 长期记忆（2026-09-23 交付，M4 进行中）：

- 作用域：**用户级**（`userId`），换个会话照样召回；类型只有 `FACT`（客观事实）与 `PREFERENCE`（对回答方式的要求）
- 抽取：运行收口之后**异步**触发（不占会话队列、失败不影响运行），走**优先便宜档**，三道节流（累计消息数 / 新增条数 / 最小间隔）；
  抽取的对话 token 与逐条 embedding 计入用户额度
- 归并：只看模型判的 `supersedes` + **人工优先**（`MANUAL` 条目永不被抽取覆盖）；同槽位靠 `unique (user_id, kind, mem_key)` + 条件更新单赢家
- 召回：阈值默认继承 `retrieval.embedding.min-score`；三重预算（阈值 / 条数 / 字符）**放不下整条丢、不截断**；
  query = 本轮输入 + 摘要前 N 字（用户只说「那它呢」也召得回来）
- 接口：`GET/POST /api/v1/users/{id}/memories`、`DELETE .../memories/{memoryId}`（硬删 + 同事务清事件行）、
  `GET .../memories/preview`（命中 / 分数 / 丢弃原因 / 最终注入块）
- 默认值：**召回开、抽取关**（抽取要调模型花钱，与摘要 / OCR 同口径），`AIFLOW_LONG_MEMORY_EXTRACT_ENABLED=true` 打开
- 控制台第 3.5 节是长期记忆抽屉（列表 / 手工新增 / 删除 / 召回预览），第 3 节的装配预览会显示「长期记忆 有/无」
- 实测：全量 `mvn test` 493 条 0 失败；真实栈 `script/verify-long-memory-flow.ps1` 22/22 PASS（含真实 qwen 抽取落条目）；
  审批回归四条 + 短期记忆 20/20 全 PASS；控制台抽屉浏览器手验通过（零 JS 报错）

M4-3（2026-10-09，A/B 已交付）：

- 设计见 `docs/m4-3-design.md`，按持久调度/Redis 事件与租约 → 画像 → Gatling → ASR/TTS 拆分交付，M3 云知识库真实联调并行排期。
- A 实施内容：V9 `session_dispatch_tasks` 保留 PROCESS/RESUME 执行段；消息/运行/任务同事务，429 整体回滚；同会话按共享 task seq 领取，租约围栏保护持久写入，审批挂起释放锁，跨实例取消记在任务上。
- Redis 事件模式保持数字 `seq`/SSE `id`，每 run 的 Stream 精确保留最多 2000 条，最后发布后 24 小时过期；序号 counter 不自动过期。普通 XREAD 广播、每个 SSE 独立有界发送队列，非法游标 400、缺口/过期 409，已建流缺口发无 seq 的 `STREAM_ERROR` 后关闭。
- Compose Redis 增加 AOF everysec 与命名数据卷；本轮应用/Redis 重启、容器重建后的回放均验收通过。PG 快照是状态事实来源；PG 与 Redis 未用 outbox 原子提交，事件故障不改变业务终态，AOF 主机故障存在最后一秒写入丢失边界。
- A 的真实验证结果记录在 `target/m4-3/`：双实例顺序/审批/取消、Redis 回放与游标、TTL/失锁、429 回滚、标记进程崩溃回收均已按脚本执行；B 的画像与显式偏好已验收。C 的当前诊断包已完成 S0/S1/S2、快速连发顺序、背压/取消、S3a/S3b 逐步建连1000规模和慢消费者演练；1000 VU 瞬时同时起跑失败边界保留。D0 音频端口、校验、mock 与能力报告已交付；D1 mock API 已验收，证据见 [`docs/m4-3d-d1-validation.md`](docs/m4-3d-d1-validation.md)，真实厂商调用后续确认，方案见 [`docs/m4-3d-plan.md`](docs/m4-3d-plan.md)。

### M4-3A 配置与验证

`aiflow.dispatch.enabled=true` 默认启用 PG 调度，`aiflow.dispatch.worker-enabled=false` 可把实例设成仅接收 API 请求。
事件模式默认 `memory`；双实例验证显式启用 `aiflow.events.mode=redis` 和 `aiflow.engine.session.redis-lock-enabled=true`。
事件配置为 `aiflow.events.max-entries=2000`、`retention=24h`、`read-block=1s`、`read-batch-size=256`、
`read-retry-limit=3`、`sse-queue-capacity=2048`。保留条数与时长可以缩短，上限分别为 2000 与 24 小时。

验证实例统一使用 `aiflow.provider.mode=mock`、`aiflow.retrieval.embedding.mode=mock`，关闭摘要与长期抽取；
这些脚本使用合成输入，启动配置由调用者提供，不读取或修改凭证文件。示例中 8082/8083 是独立验收端口：

```powershell
# 两个已启动的 mock 实例共享 PG/Redis；脚本可按参数修改端口
.\script\verify-distributed-flow.ps1 -BaseUrlA http://localhost:8082 -BaseUrlB http://localhost:8083
.\script\verify-redis-events.ps1 -BaseUrlA http://localhost:8082 -BaseUrlB http://localhost:8083

# Redis 原子脚本/Stream 合约测试使用真实 Redis，默认 localhost:16379
mvn -s script/maven-settings.xml '-Dtest=RedisRunEventBusIntegrationTest' '-Daiflow.redis.integration=true' test

# 外部重启应用或 Redis 后，用此前报告中的 runId 证明持久回放
.\script\verify-redis-events.ps1 -SkipRedisContractTests -ReplayRunId '<previous-run-id>' `
  -OutFile target/m4-3/redis-events-after-restart.json

# 实例预先配置小 TTL：--aiflow.engine.session.lock-ttl-ms=1000 --aiflow.dispatch.lease=1s
.\script\verify-dispatch-failures.ps1 -LockTtlMillis 1000 -TaskLeaseMillis 1000
```

`verify-distributed-flow.ps1` 用 PG 只读查询核对顺序和执行段重叠，也验跨 API 来源的审批、取消与幂等。
加 `-PreApprovalToolId skill.list` 可验前置工具不重放，需要实例显式开启 tools/skills。
若要证明取消请求来自另一个 worker 进程，B 启动时关闭 worker，并传 `-RequireReceiverOnlyB`；
两端 worker 都开时，报告只证明跨 API 来源，不把 HTTP 接收实例当成实际执行 owner。

崩溃验证分两段：仅 A 开 worker、B 仅接收请求；A 的 JVM 启动参数包含
`-Daiflow.validation.run-id=<unique-marker>`，传 `-SkipLeaseChecks -CrashEnginePid <pid> -CrashProcessMarker <unique-marker>`。
脚本只终止与标记匹配的 Java 引擎进程，报告停在 `crashed-awaiting-survivor`。
调用者启动 B worker 后，再传 `-RecoveryReport <crash-report-path>` 验 RUNNING 失败不重放、QUEUED 继续推进。
429 验证可另传 `-CapacityBaseUrl <worker-disabled-instance> -QueueCapacity <configured-capacity>`。
脚本不删除文件、数据库行或卷；Redis 故障注入仅操作本轮新建合成会话的锁并用 TTL 收口。

`GET /api/v1/runs/dispatch/status` 可查 worker 状态；`GET /api/v1/runs/events/status` 可查总线模式、发布/读取失败、
reader/subscription/connection 数和 SSE overflow。相同计数与当前数量通过 `/actuator/prometheus` 输出，
指标只以模式为标签，不把 runId/sessionId 当标签。

### M4-3B 用户画像与显式偏好

契约见 [profile-design.md](docs/profile-design.md)，本轮验收见 [m4-3b-validation.md](docs/m4-3b-validation.md)。
V10 `user_profiles` 只存启停、统计起点、时区与版本，不复制偏好正文或保存聚合快照。画像默认关闭，GET 不建行；
PUT/DELETE 必须提交 `expectedVersion`，旧页面版本返回 409。重置为逻辑停用，保留源记录；再次启用从新起点统计，持续启用时改时区保留起点。

| 接口 | 行为 |
| --- | --- |
| GET `/api/v1/users/{userId}/profile` | 读取控制状态及最近 30 天与启用起点中较晚者以来的实时统计 |
| PUT 同路径 | 提交 `aggregationEnabled`、`timezone`、`expectedVersion`，更新状态 |
| DELETE 同路径 | query `expectedVersion`，重置统计视图并停用，保留运行、审计和长期记忆 |
| GET `.../profile/preview` | 查看偏好来源、生效/忽略原因；支持 `profilePreference=none` 和显式 `model` |

运行/工作流/时段按 `created_at` 统计，时段转换到所选时区；工具统计使用持久审计，SUCCESS/REPLAYED/FAILED/DENIED 分列，并说明审计保留期与本实例丢写情况。
对话 token 按 chat/vision 节点尝试的开始时间与 output 汇总，mock/真实/未知 provider 分列，缺失或非法用量计为 unknown。
统计不展示消息和工具参数，不推断语义话题；已记录对话 token 不含摘要、抽取或 embedding 用量，**不是完整账单**。

只应用长期记忆中 `MANUAL/PREFERENCE` 的 `response-style=concise|balanced|detailed`、`model-policy=default|cheap-first`，映射固定规则，不注入任意正文。
chat/vision 与记忆预览共用装配，预览新增 `profileIncluded`；`profilePreference=none` 禁用画像偏好，显式 model 不受画像 cheap-first 改档，预算 SOFT/HARD 仍按原规则处理。
停用画像保留既有长期记忆召回；当前仍缺 Subject→userId 归属映射，画像 API 延续本地开发边界。

真实验收只用合成数据，强制 mock 对话与 embedding，关闭真实端点/探活、云知识库及长期抽取；短期记忆回归开启 mock 摘要。
已启动对应配置实例后可执行：

```powershell
.\script\verify-profile-flow.ps1 -BaseUrl http://localhost:8082 `
  -OutFile target/m4-3b/profile-flow-verify.json
```

本轮新增 37 条测试；全量 **606 条、0 失败、0 错误、1 跳过**，真实 Redis 集成已开启，唯一跳过为真实 embedding 校准；打包成功。
真实画像 23/23、短期记忆 20/20、审批 4/4、长期记忆 21/21 通过。长期记忆回归未传 `-ExpectExtraction`，仅清理脚本自己创建的合成条目，未删除原数据；真实付费抽取未重跑。
控制台真实浏览器验证启停、时区、白名单/none/显式 model、逻辑重置保留两条手工偏好及用户切换，无浏览器 warn/error 日志。
脚本报告和截图见 `target/m4-3b/`（截图 `profile-ui-reset.jpg`）。

### M4-3C mock 负载验证

方案见 [m4-3c-plan.md](docs/m4-3c-plan.md)，结果与限制见 [m4-3c-validation.md](docs/m4-3c-validation.md)。
独立 Gatling `load-test` profile 不污染根项目依赖；所有负载强制 mock，配置快照与报告保留在 `target/m4-3c/`。
已修复终态 SSE 清理、正常 Redis 退订误计故障、续期与任务收口竞争，以及已提交SSE断连后回写JSON的问题；当前诊断包全量回归635条、0失败、0错误、1条真实embedding校准跳过，打包通过。

- S1：10会话各10轮全部成功，TTFT p50/p99为1169.98/2759.40ms，完成吞吐3.7645run/s。
- S2：200会话一轮全部成功，workers=16，TTFT p99为35503.44ms，完成吞吐5.1076run/s；失租、事件失败及残留活动资源均0。200VU包括排队，不能解释为200个同时执行。
- 同会话685ms内连续提交10条，顺序与task seq一致、无执行段重叠；429为102ms、无事务残留、幂等键可重试；4条delay同步并行取消后605ms内run/node/task收口。
- 10月10日1000 VU同时起跑曾因连接预检失败而未通过；当前诊断包用20秒逐步建连复验S3a/S3b各1000条连接，验证的是指定mock环境的常连/reader/回收行为，不外推瞬时突发容量或生产容量。
- queue16慢消费者演练验证raw socket不读取时overflow恰增1，正常SSE仍连续收到终态并EOF，PG/Redis与资源收口一致；首轮75秒超时失败报告保留。

```powershell
# 已有V10与Redis；独立mock实例，禁止真实模型压测，Flyway关闭
powershell -NoProfile -ExecutionPolicy Bypass -File .\script\start-load-engine.ps1 `
  -Port 8082 -Workers 16 -QueueCapacity 1000
mvn -f script/gatling/pom.xml -Pload-test `
  '-Dload.baseUrl=http://localhost:8082' `
  '-Dgatling.simulationClass=com.aiflow.gatling.BaselineSimulation' test-compile gatling:test
# 背压/取消另启 QueueCapacity=2 的专用实例后执行 verify-load-tail.ps1
```

### M4-3D 语音最小链路（D0、D1 已完成）

方案见 [`docs/m4-3d-plan.md`](docs/m4-3d-plan.md)。D0 实现独立 `engine/audio` 端口、mock 契约、WAV 格式校验与 `asr`/`tts` 能力报告；D1 已接入 mock 同步 API。D2 增加 `asr.transcribe` / `tts.speak` 工作流节点与独立一次性音频面板：节点只传 run-scoped 临时 UUID 句柄或文本，句柄不落库、不跨实例恢复；控制台不把音频写入会话、附件或浏览器持久存储。默认仍关闭，真实厂商 HTTP 协议桩待协议确认。

默认限制为 WAV 未压缩 PCM、单声道、16kHz、16bit、30 秒和 5MiB；合成文本最多 2000 个 Unicode 码点。`AIFLOW_AUDIO_ENABLED=true` 与 `AIFLOW_AUDIO_MODE=mock` 显式启用 mock；可分别通过 `AIFLOW_AUDIO_ASR_ENABLED` / `AIFLOW_AUDIO_TTS_ENABLED` 关闭通道。能力接口的语音状态为 `mock` 或 `unavailable`，不把静音/模拟转写当真实质量。D0 只记录凭证环境变量名 `AIFLOW_AUDIO_ASR_API_KEY` / `AIFLOW_AUDIO_TTS_API_KEY`，不读取变量值。

校验器支持按限制配置 1..192000Hz、1/2 声道、8/16/24/32bit PCM；mock TTS 固定输出 16kHz/单声道/16bit、100..2000ms 静音，`voice` 不影响输出。能力报告仅保证至少一个最小样本可处理；ASR 限制要容纳一个完整 PCM 帧，TTS 至少容纳 100ms/3244bytes，否则为 `unavailable`。D0 新增 52 条测试，全量 687 条、0 失败、0 错误、1 条预期跳过，离线打包成功；证据见 [`docs/m4-3d-validation.md`](docs/m4-3d-validation.md)。

### D1 mock 同步音频 API（已完成并验收）

启动时显式设置 `AIFLOW_AUDIO_ENABLED=true`、`AIFLOW_AUDIO_MODE=mock`，分别由 ASR/TTS 通道开关控制；model 使用通道配置，调用方传入的 model 不透传。

| 接口 | 输入 | 成功输出 |
| --- | --- | --- |
| `POST /api/v1/audio/transcriptions` | multipart `file`，文件媒体类型 `audio/wav` / `audio/x-wav` / `audio/wave` | `ApiResponse` JSON：模拟 `text`、`provider`、`model`、`durationMs`、`limits` |
| `POST /api/v1/audio/speech` | JSON `text`，可选字符串 `voice` / `format`（`wav` / `audio/wav`） | 原始 WAV 字节；固定 `speech.wav` 文件名、准确长度、`X-Audio-Provider/Model/Duration-Ms` 元信息头 |

两个成功响应均 `Cache-Control: no-store`。转写输入默认≤5MiB/30秒；合成文本去除首尾空白后≤2000 Unicode 码点。`text`/`voice`/`format` 的数字、布尔、数组或对象均拒绝为 400。参数/结构错误400、媒体/PCM参数不支持415、输入大小/时长/码点超限413、通道不可用503、上游失败或无效输出502、上游超时504，错误统一 JSON，即使调用方 `Accept: audio/wav`。上传按独立上限限长读取；Servlet 的请求期临时文件由容器清理，应用不保存音频原件。

## 分级路由与本地兜底（M3 起点）

档位由 `aiflow.provider.order` 排序，默认 `cloud -> local -> mock`：质量优先（云端加速与复杂任务）、本地档留在链上做兜底（断网时自动顶上）、mock 只兜住流程。
为什么默认云端优先：本机 1.5B 的兜底模型在「从资料里抽答案」这类活上明显不如云端（实测见下文「本地对话档」），把质量差的档位放在前面等于默认输出差答案。每个档位是一个 OpenAI 兼容端点，本地与云端同一份契约：

```yaml
aiflow:
  provider:
    order: [cloud, local, mock]     # 质量优先；想离线优先就换回 [local, cloud, mock]
    endpoints:
      local:
        tier: local
        base-url: http://localhost:11434/v1      # Ollama / vLLM
        model: qwen2.5:1.5b
        capabilities: [chat]
      cloud:
        tier: cloud
        base-url: ${AIFLOW_CLOUD_LLM_BASE_URL:}  # 空地址 = 未配置，不参与降级统计
        model: ${AIFLOW_CLOUD_LLM_MODEL:}
        capabilities: [chat, vision]             # 多模态档：llm.vision 节点用它
```

看现在到底能干什么（控制台也展示这份数据）：

```bash
curl -s localhost:8080/api/v1/capabilities
```

```json
{
  "level": "L3",
  "levelName": "预设话术（没有可用的真实模型）",
  "notice": "已降级：配置的档位 local 不可用，当前由 mock 承接；向量化为 mock-embedding（离线演示档）…",
  "channels": [
    { "channel": "chat", "status": "degraded", "activeProvider": "mock", "activeTier": "mock",
      "candidates": [
        { "id": "local", "tier": "local", "available": false, "detail": "open：探活失败: ConnectException" },
        { "id": "mock",  "tier": "mock",  "available": true,  "detail": "确定性模拟回复（离线联调与压测用…）" }
      ] }
  ]
}
```

上例就是**没装任何本地模型**时的真实返回（本机实测）：本地档被判死 -> 熔断打开 -> 请求直接落到 mock，而不是每个请求白等一次连接超时。

降级会在 run 快照里留痕，事故复盘时不用翻日志：

```
llm.chat -> {"provider": "mock", "skippedTiers": ["local"], "reply": "（mock）已收到：你好。…"}
                └─ 探活判死、直接跳过     └─ 尝试过但失败时记在这里，字段名 degradedFrom
```

行为细节（都是踩过才写下来的）：

- 已经向用户推过 SSE 增量的调用**不换档**：两档的输出拼在一起是一段读不通的话
- 用户取消（线程中断）不换档也不熔断，那是取消不是故障
- 探活 401/403 记为不可用（服务活着但密钥不对）；其余 4xx 记为可达（有些网关没有 `/models`）
- 全部档位都不可用时仍按 order 试一遍：健康状态是大概率，不是绝对

本机装本地模型（可选，脚本支持断点续传）：

```powershell
powershell -File script\setup-local-embedding.ps1     # 装 Ollama + 拉 bge-m3 + qwen2.5:1.5b
```

> 实测提醒：本机到 ollama.com 的带宽在 20-600 KB/s 之间波动（Ollama 安装包 1.1GB + bge-m3 1.2GB），下载要数小时；2026-09-20 已在本机装好 Ollama 0.34.2 并拉完 `bge-m3`（1.16GB）。**对话模型 `qwen2.5:1.5b` 尚未拉取**，所以本地对话档现在是 down（探活会明说「模型列表中不含 qwen2.5:1.5b」），对话由云端档承接。云端档不需要下载，配上网关地址与密钥即可（密钥只走环境变量）。
## 图像问答与附件（M3）

图片走「先上传拿 attachmentId，再随消息引用」两步；`llm.vision` 节点把引用解析成图片，交给同时具备
`chat` + `vision` 能力的档位（云端 VL / 本地多模态模型）：

```bash
# 1) 上传图片（png / jpeg / webp / gif，按文件头识别，改扩展名无效；默认上限 10MB）
curl -s -F "file=@D:\pics\receipt.png" localhost:8080/api/v1/attachments
# -> {"attachmentId":"...","mimeType":"image/png","sizeBytes":12345,"sha256":"...","url":"/api/v1/attachments/..."}

# 2) 建会话时绑定 vision-chat，再把 attachmentId 放进消息
curl -s -X POST localhost:8080/api/v1/sessions -H "Content-Type: application/json" \
  -d "{\"userId\":\"<userId>\",\"workflowName\":\"vision-chat\"}"
curl -s -X POST localhost:8080/api/v1/sessions/<sessionId>/messages -H "Content-Type: application/json" \
  -d "{\"clientMsgId\":\"m1\",\"content\":\"这张发票的抬头是什么？\",\"attachmentIds\":[\"<attachmentId>\"]}"
```

没有真实多模态档位时由 mock 承接，回复会自报家门（`（mock 视觉）已收到 1 张图片…只证明图片进得来`），
节点快照里 `provider=mock` + `imageCount=1` 是第二条留痕 —— 不假装成真实模型的描述。

安全取舍：图片以 `data:` 内联上传给厂商；**不接受本地文件路径**（既是 SSRF 面也是目录泄露面），
直链只认 `http(s)://`。日志、快照与元数据只留 id / 类型 / 大小 / sha256，不留 base64。

## 工具调用、内容安全与额度（M3 收尾）

三件事一句话：**引擎能主动出网（但必须显式打开）**、**进出的文本要过规则层**、**每个用户的模型开销有账本**。

### 工具调用（默认全关）

```yaml
aiflow:
  tools:
    enabled: true                      # 打开就等于允许引擎主动出网，必须是显式选择
    allow-hosts: [api.example.com]     # 只允许这些 host（支持 *.example.com 通配子域）
    allow-private-hosts: []            # 解析到内网/回环的地址默认一律拒绝（防 SSRF）
    allow-ports: [80, 443]
    max-calls-per-run: 8
    loop-threshold: 3                  # 同（工具 + 入参）指纹重复超过该次数即判为工具循环
    registry:
      weather:
        description: 查询天气
        method: GET
        url: https://api.example.com/weather
        idempotency: content           # content = 同参数同结果可复用；none = 每次都发
```

工作流里加一个节点即可：`{"id":"call","type":"tool.call","params":{"tool":"weather","args":{"city":"${input}"}}}`。

四道校验全过才会发出请求：协议（只 http/https，带 userinfo 直接拒）-> 端口白名单 -> host 白名单（按域名边界匹配，
`evilexample.com` 不会匹配 `*.example.com`）-> 解析后的地址不得是回环/内网/链路本地（含 `169.254.169.254`）。
另有运行级护栏：调用次数上限、同指纹重复超阈值判循环、幂等缓存、`3xx` 拒绝跟随重定向。
工具返回值与 RAG 切片同级别对待：**不可信数据**，不进系统提示词的可信区。

### 工具粒度授权与调用审计（M3 收尾）

两个问题分开答：`aiflow.tools.enabled` 管「引擎能不能调工具」，
`aiflow.tools.policy` 管「**这个人**能不能调**这个工具**」。

```yaml
aiflow:
  tools:
    policy:
      default-allow: true        # 没有任何规则命中时是否放行（加规则是为了收紧，不是默认全拒）
      require-user: false        # true = 没有用户上下文的调用（定时任务）一律拒绝
      rules:                     # 按顺序匹配，首个命中生效；用户支持 * 通配，deny 优先于 allow
        - tool: "mcp.filesystem.write_*"
          allow-users: ["<userId>"]
          reason: "写文件属于受限操作"
    audit:
      enabled: true
      max-entries: 1000          # 内存环形缓冲（最近 N 条，超出丢最老的）
      persist: true              # 落库留痕（表 tool_audit_events）：跨重启追溯
      queue-size: 2048           # 写库队列；满了丢最老的，丢多少计数并暴露在 /tools/audit
      retention-days: 30         # 落库保留期，<=0 表示不清理
      purge-interval-minutes: 360  # 保留期清理间隔（写入线程顺带做，不引入新调度器）
```

三条语义写死在测试里（`ToolAuthorizerTest`）：**首个命中生效**、**deny 压 allow**、
**空白名单不等于"所有人"**（没有用户上下文时只有显式写 `"*"` 的规则才放行）。
拒绝发生在**调用之前**：被策略挡住的调用不会发出任何请求（集成测试用桩服务验证了调用计数不变），
拒绝原因会带进节点错误里，用户看到的是"为什么"，不只是"不行"。

查现状：

```bash
curl "http://localhost:8080/api/v1/tools/policy"                          # 规则清单与默认口径
curl "http://localhost:8080/api/v1/tools/policy/evaluate?toolId=skill.read&userId=<uuid>"
curl "http://localhost:8080/api/v1/tools/audit?toolId=skill.read&limit=20"          # 最近的调用/拒绝
curl "http://localhost:8080/api/v1/tools/audit?outcome=DENIED&limit=20"             # 只看被拒的
```

审计每条记录包含：**谁**（userId / sessionId / runId）、**调了什么**（toolId + kind）、**结果**
（`SUCCESS` / `REPLAYED` 命中幂等缓存 / `FAILED` / `DENIED`）、耗时、拒绝或失败原因。
参数**只记指纹不记原文**：工具参数里常有手机号、token 这类东西，审计要能定位"是不是同一个调用"，
但不该变成第二个敏感数据仓库。同一份记录也会打到 INFO 日志（logger 名 `aiflow.audit`）。

审计有**三条腿**，一次 `record` 同时喂：内存环形缓冲（"此刻能查"，代价近零，重启即空）、
结构化日志（事实流）、落库 `tool_audit_events`（跨重启追溯）。落库是**异步**的 ——
工具调用路径上只多一次入队，写库在自己的线程里 64 条一批地做，因此库慢不会让对话变慢。

异步必须配诚实的降级语义，否则"审计不丢"只是口号：

- 队列满**丢最老的**：近期记录更值得留；丢了多少计数在响应的 `droppedWrites` 里。
- 写失败**只计数不重试**：无条件重试会把队列堵死，最后丢得更多；失败批次数在 `failedWriteBatches` 里。
- 查询优先读库，库不可用时自动回退缓冲，并把来源写在 `store` 字段（`database` / `memory`）——
  看结果的人必须知道这份结果能回溯多远。
- 保留期默认 30 天，清理在写入线程里顺带执行（`retention-days` / `purge-interval-minutes`）。

顺带钉住一条引擎语义：**策略拒绝不重试**。拒绝是确定性的（同一个用户、同一条策略，试几次都一样），
重试只会多等一轮退避、还让审计多出一行"被拒"（审计记的是尝试次数，重复记录会把排障带偏）。

实测（2026-09-22，真实 PostgreSQL + Flyway V4）：

```bash
# 工具指向引擎自身的 /api/v1/capabilities（不出网）；一个可调、一个被策略对所有用户关闭
curl -X POST http://localhost:8080/api/v1/workflows -H "Content-Type: application/json" -d @target/e2e-audit-ok.json
curl -X POST http://localhost:8080/api/v1/workflows -H "Content-Type: application/json" -d @target/e2e-audit-denied.json
# -> 两个 run：SUCCEEDED / FAILED（error=工具未授权: 端到端验证：这个工具对所有人关闭）

curl "http://localhost:8080/api/v1/tools/audit?limit=10"
# -> store=database persistent=true storeTotal=2 pendingWrites=0 failedWriteBatches=0
#    DENIED  audit-denied-tool  kind=HTTP durationMs=0  detail=工具未授权: ...
#    SUCCESS demo-internal      kind=HTTP durationMs=29 detail=httpStatus=200

docker exec aiflow-postgres psql -U aiflow -d aiflow -c "select version, description, success from flyway_schema_history order by installed_rank;"
# -> 4 | tool audit events | t

# 重启引擎后再查：内存缓冲空了，但记录还在（跨重启追溯的真正证据）
curl "http://localhost:8080/api/v1/tools/audit?limit=10"
# -> store=database bufferSize=0 storeTotal=2 count=2（两条记录原样返回，可按 userId / outcome 过滤）
```

### MCP 服务端与本机技能库（M3）

HTTP 工具解决「调用已知的固定接口」，但真实世界里的能力大多以两种形态存在：**别人已经写好的 MCP 服务端**，和
**本机 Agent 的技能文件**。这两件事共用同一个工具白名单（`tool.call` 节点只认一个 `tool` 参数），
区别只在来源与执行方式；三者的开关相互独立 —— HTTP 工具出网、MCP 拉起本地进程、技能库只读本地文件，风险不是一类。

```yaml
aiflow:
  tools:
    enabled: true                # 总闸：为 false 时三个来源都不注册
    mcp:
      enabled: true
      id-prefix: "mcp."          # 工具 id = mcp.<serverId>.<toolName>，与 HTTP 工具同名时保留先注册的
      servers:                   # 手工声明的服务端（stdio），与下面的导入结果合并
        - id: filesystem
          command: npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "D:/work1"]
      imports:                   # 从已装 Agent 的配置里导入 [mcp_servers.*]
        - path: ~/.codex/config.toml
          id-prefix: codex-
        - path: ~/.claude.json
          id-prefix: claude-
    skills:
      enabled: true
      roots:                     # 顺序即优先级，同名技能以先出现的为准
        - ~/.agents/skills
        - ~/.claude/skills
        - ~/.codex/skills
        - ~/.codex/plugins/cache
      max-body-chars: 20000      # skill.read 的正文上限（超出截断），防止把长文档塞爆提示词
```

**为什么技能库只暴露两个工具**（`skill.list` / `skill.read`）而不是「一技能一工具」：技能有 25 个且会变，
塞进工具清单会让编排者和模型每次都要读一遍完整目录。两个工具的语义是「先搜索、再按需读正文」，新增技能不需要改任何配置。

**接入一个新工具的两条路**（都不需要改代码）：

1. 本机已经有 Codex / Claude 的 MCP 配置：填 `imports` 指向它，重启后自动注册。2026-09-20 实测：导入 `~/.codex/config.toml`
   得到 1 个 stdio 服务端（`codex-node_repl`，4 个工具）；`~/.claude.json` 没有 `mcpServers` 字段，被跳过并记日志
   （**跳过不等于失败**，真正的失败原因在 `GET /api/v1/tools` 的 `mcp.failures` 里）。
2. 别的服务端：在 `servers` 里加一条 —— `command` + `args` 是 stdio（本地子进程），`url` 是 Streamable HTTP（远程单端点）。
   两种形态的权限面不同，校验也就不同：stdio 只看「命令来自哪个配置文件」，HTTP 还要过 `allow-hosts` / `allow-ports` /
   `allow-private-hosts` 那套出网白名单（与 HTTP 工具同一套，见下面的「HTTP 形态与远程鉴权」）。

运行时自检与热更新：

```bash
curl http://localhost:8080/api/v1/tools                  # 白名单 + 每个 MCP 服务端的工具数 + 技能库根目录与技能数
curl -X POST http://localhost:8080/api/v1/tools/reload   # 重新发现 MCP 服务端与技能文件 + 重装白名单快照，不重启进程
```

MCP 客户端是自己实现的（`initialize` -> `tools/list` -> `tools/call`），三种传输共用同一套装配与调用逻辑，
差异收在一个 `McpClient` 接口后面：

- **stdio**：newline-delimited JSON-RPC 2.0。每个服务端一条常驻进程、串行调用，stderr 单独抽干并保留末尾若干行
  （拉起失败时把 stderr 尾部贴进失败原因），调用超时即杀进程（不留下半死不活的子进程）。
- **Streamable HTTP**：单端点 `POST`，响应可以是 `application/json`，也可以是一条 `text/event-stream` 流。
  服务端下发的 `Mcp-Session-Id` 会被回带到后续请求；服务端回 `404` 表示会话失效，客户端**重新握手并重试一次**
  （只一次：一直失效说明是配置或鉴权问题，重试掩盖不了）。SSE 解析跳过心跳注释与无关事件，只认 id 对得上的那条；
  读取带超时，超时关流 —— HTTP 形态没有进程可回收。
- **老式 SSE 双端点**：先 `GET` 建一条常驻流，服务端用 `event: endpoint` 告诉客户端「消息往哪 POST」；请求与
  响应分离，靠 JSON-RPC id 在流上配对。端点地址是**服务端下发的输入**，所以相对路径按建流地址解析之后还要再过一遍
  SSRF 白名单 —— 只校验第一次连接，等于让对方指定我们去连谁。流断了在途请求立刻失败，下一条请求重新建流、重新握手、
  **只重试一次**（一直断说明是配置或对端问题，重试掩盖不了）。

单个服务端起不来只记 `failures`，**不炸整个 Spring 上下文** —— 一个可选的工具配置错，不该让引擎起不来。

三个来源共用同一套运行级护栏（次数上限、循环指纹、幂等缓存），护栏与来源无关；MCP 的 `isError=true` 按节点失败处理
（与 HTTP 4xx/5xx 一致），输出快照里带 `kind=skill|mcp` 与 `serverId`/`mcpTool`，排障时能直接看出走的是哪条路。

```bash
# 实测 m3-skill-demo：start -> skill.list(query=resume) -> skill.read(great-resume) -> end，三节点 SUCCEEDED
# 实测 m3-mcp-demo  ：start -> mcp.codex-node_repl.js(code=nodeRepl.write(...)) -> end
#   -> {"body":"aiflow-m3: 42","kind":"mcp","serverId":"codex-node_repl","mcpTool":"js","durationMs":5}
```

#### HTTP 形态与远程鉴权（M3 收尾）

stdio 只能接「本机已经装好的东西」，而真实世界里的 MCP 服务端越来越多是**远程**的（一个 URL + 一个 token）。
url 形态走的是另一条传输，但配置、发现、调用、护栏全都是同一条路径：

```yaml
aiflow:
  tools:
    allow-hosts: [mcp.example.com]                      # HTTP 形态必须过这三项，否则装配期就失败
    allow-private-hosts: []                             # 内网地址默认一律拒绝（与 HTTP 工具同一套 SSRF 规则）
    allow-ports: [80, 443]
    mcp:
      servers:
        - id: remote-demo
          url: https://mcp.example.com/mcp
          auth-token-env: AIFLOW_MCP_DEMO_TOKEN         # 值只来自环境变量，yml 里只有变量名
          headers:
            X-Tenant: "${AIFLOW_MCP_DEMO_TENANT:-public}"   # 也支持 ${VAR} / ${VAR:-默认值}
          allowed-tools: [search]                       # 空 = 放行 tools/list 回来的全部工具
```

三条与鉴权有关的取舍：

1. **凭证只有两个入口**：`auth-token-env`（读环境变量拼成 `Authorization: Bearer ...`）与 headers 里的 `${VAR}`
   占位符。明文密钥在这条路径上写不进配置 —— 不是靠自觉，是语法上没给位置。
2. **变量没设就装配失败**，不静默留空：静默的结果是一串 401，或者更糟 —— 一次匿名调用。
3. **401/403 的错误只提示「检查哪个环境变量」**，不回显任何头部值；服务端下发的会话 id 也要先过字符集与长度校验才使用。

`GET /api/v1/tools` 的 `mcp.servers` 会列出每个服务端的 `transport`（stdio / http / sse）、`url`、`tools`、`source`、
`mcp.failures` 给出「为什么没起来」——「跳过了」和「没配」必须能区分开。

2026-09-20 实测（`FakeMcpHttpServerMain` 作为带鉴权 + 强制会话的假服务端）：

```bash
# 装配：{"id":"http-demo","transport":"http","tools":["echo","boom","slow"]}，failures 为空
# 运行：start -> tool.call(mcp.http-demo.echo, args.msg=来自引擎的问候) -> end
#   -> {"toolText":"http-echo:来自引擎的问候"}（run SUCCEEDED）
# 审计：GET /api/v1/tools/audit?toolId=mcp.http-demo.echo
#   -> SUCCESS mcp.http-demo.echo kind=MCP durationMs=26 detail=bodyChars=17
```

假服务端强制校验 `Mcp-Session-Id`（不回带就回 404）并要求 Bearer token，所以这一次成功同时证明了「会话回带」与
「凭证从环境变量注入」两件事。

#### OAuth 令牌与自动刷新（M3 收尾）

静态 Bearer 只够「个人 token 粘在配置里」。接公司网关这类服务端，令牌是短命的，得自动刷：

```yaml
aiflow:
  tools:
    mcp:
      servers:
        - id: oauth-demo
          url: https://mcp.example.com/mcp
          transport: http
          oauth:
            token-url: https://auth.example.com/oauth/token
            client-id-env: AIFLOW_MCP_CLIENT_ID            # 三个值一律只写变量名
            client-secret-env: AIFLOW_MCP_CLIENT_SECRET
            refresh-token-env: AIFLOW_MCP_REFRESH_TOKEN    # 配了它走 refresh_token，否则 client_credentials
            scope: mcp.read
            early-refresh-seconds: 60                      # 到期前多少秒提前刷（默认 60）
```

四种手法里 OAuth 与 `auth-token-env` **二选一，配置期就报错**：运行期再猜「这条请求该用哪个凭证」是事故来源。
三个取舍：

1. **单飞**：并发调用同时撞上令牌过期时只发一次刷新请求，其余等同一个 future（4 并发只刷一次有测试盯着）。
2. **令牌端点与工具端点同一套 SSRF 白名单** —— 刷新流程不能变成绕过出网限制的后门。
3. **`GET /api/v1/tools` 里只看得到「还剩多少秒过期」**，令牌本身不进日志、不进快照；401 也只提示检查哪个环境变量。

#### 老式 SSE 双端点与热更新一致性（M3 收尾）

Claude 配置里 `type: "sse"` 的服务端是**两个端点**：一条常驻流 + 一个由服务端下发的投递地址。它与 Streamable HTTP
是完全不同的状态机，所以单独实现（上面「老式 SSE 双端点」那条 bullet 讲了客户端的三个坑）。导入这类配置不再跳过，
而是按 `transport: sse` 装配。

热更新（`POST /api/v1/tools/reload`）的一致性规则是**先装新的、再等旧的跑完**：

- 新连接装好才交接，reload 过程中**在途调用不会被掐**；等在途调用退场有上限（`aiflow.tools.mcp.drain-timeout-ms`，默认 5000），
  超时改为异步强关并留 WARN —— 一个卡住的旧连接不该把 reload 一起拖住。
- 每次装配记 `version`（transport | id | 工具指纹的 sha256 前 12 位）与 `generation`，快照里能直接看出「这个服务端的工具
  这次和上次是不是同一套」。
- 调用前做一致性校验：工具消失、服务端不可用、定义变了（schema / 描述指纹不同）都**明确失败并提示重新发现**，
  而不是把旧参数发往已经变了的接口。
- reload 不只是「重新发现」：发现结果要**重装进白名单快照**才真的生效。注册表持有的是一份不可变快照（读侧无锁），
  重装 = 整体替换，run 线程不会读到「改了一半」的映射；HTTP 工具的白名单沿用启动时的结果 —— 改配置文件本就该重启，
  混进重扫会让跑了一半的 run 引用到换了定义的工具。

```bash
# 2026-09-21 实测（FakeMcpSseServerMain + FakeOAuthServerMain 作为真配置里的服务端）：
# 装配：{"id":"legacy-e2e","transport":"sse","tools":["echo","boom","slow"],
#        "auth":"oauth(client_credentials, 3576s 后过期)"}，failures 为空
# 调用：start -> tool.call(mcp.legacy-e2e.echo, args.msg=来自引擎的老式SSE问候) -> end
#   -> {"reply":"sse-echo:来自引擎的老式SSE问候"}（run SUCCEEDED；审计 SUCCESS kind=MCP durationMs=6）
# 热更新：慢调用（5s）跑到第 1.5s 时 POST /tools/reload
#   -> reload 耗时 3584ms（等它跑完才关旧连接），该 run 最终 SUCCEEDED {"reply":"slow done"}
#   -> 快照 generation 1 -> 2、version 不变（工具集没变）、重装后白名单与发现结果一致（7 个工具）
```

### PDF 与图片入库（M3 收尾）

知识库收 **text / markdown / json / pdf / png·jpg·webp·gif**。PDF 与图片走两段式处理，
核心取舍是**能白拿的文本层白拿，拿不到才花钱 OCR**：

```yaml
aiflow:
  ingest:
    max-bytes: 20971520        # 单文件上限（解析要整体读进内存）
    max-pages: 300             # 单文档解析页数上限
    ocr:
      enabled: false           # 默认关：OCR 会调多模态模型，按页计费
      min-page-text-chars: 40  # 一页文本层少于这个字数就当扫描件
      max-pages: 10            # 单文档最多 OCR 多少页（超过直接失败，不静默截断）
      dpi: 150                 # 扫描页渲染分辨率：150 够识别正文，再高只是更贵
```

一次扫描件入库的行为：

1. 逐页抽文本层；只有「抽出的字数 < min-page-text-chars」的页才渲染成 PNG 送去 OCR；
2. OCR 走**多模态档位**（具备 `vision` 能力的模型，默认云端 `deepseek-flash`），
   提示词要求逐字转写、表格用 `|` 分隔、看不清用 □ 占位、不翻译不总结；
3. 切片**按页归属**，检索结果的引用带页码（`attendance-notice.png p.1`）。

为什么 OCR 默认关：它按页调用多模态模型（成本与用户会话是两笔账），而 PDF 文本层提取不要钱、不要模型，
永远开着。开启方式：`AIFLOW_OCR_ENABLED=true`（或改 yml）并确保有 vision 档位。

**2026-09-20 真实联调**（应用启动带 `AIFLOW_OCR_ENABLED=true`，云端 `deepseek-flash`）：

```bash
# 样本生成：java script/GenerateOcrSamples.java target/e2e（需要 pdfbox-jar 在 classpath，见脚本头注释）
# 1) 有文本层的 PDF：日志 "PDF 文本层解析完成 pages=1（无需 OCR）"，0 次模型调用，切片 page_from=1
# 2) 扫描件 PDF（图片页）：日志 "PDF 需要 OCR 的页 1 页 / 共 1 页 provider=vision-ocr"
#    -> OCR 完成 provider=cloud model=deepseek-flash chars=74（中文逐字转写正确）
# 3) PNG 图片：同走 OCR，入库后检索「忘记打卡怎么办」命中，citation=attendance-notice.png p.1
```

不支持的格式会明确报错而不是按 UTF-8 读成乱码：`.docx / .xlsx / .zip` 等已知二进制返回 400
「这份文件需要先转成文本」—— 乱码切片进了向量库，事后没人看得出是怎么进去的。

2026-10-15 起，PDF/图片、JSON、上传读取和 OCR provider 失败使用固定诊断，不回显底层报文、地址或异常链；文档索引失败仅记录切片/向量化/索引写入阶段。上传文件名中的路径分隔符、控制字符和换行统一替换为下划线，最多 200 字符，保留短扩展名。OCR 关闭或缺少能力返回 503，其他 OCR 拒绝延续 400。契约与离线验收见 [文档错误边界](docs/document-ingest-error-boundary.md) 和 [验收记录](docs/document-ingest-error-validation.md)。

#### 原件留存（对象存储，可选）

切片进 PG、**原件进对象存储**，两边各拿各擅长的：二进制动辄几 MB，塞进 PG 会把备份、复制、连接池一起拖慢，
而它的访问模式（低频、整份读、按 key 取）正好是对象存储的形状。`documents` 表只记 `object_key` / `object_bytes`。

```yaml
aiflow:
  storage:
    enabled: false                          # 默认关：不开也能完整跑通入库与检索
    endpoint: http://localhost:19000        # docker compose 起的 MinIO（S3 API）
    bucket: aiflow-originals                # 不存在会自动创建
    access-key: ${AIFLOW_STORAGE_ACCESS_KEY:}   # 只从环境变量来，不写配置文件
    secret-key: ${AIFLOW_STORAGE_SECRET_KEY:}
    timeout: 10s                           # HTTP 连接/读写及单次 call 总时限（含下载流）
```

```bash
docker compose up -d minio            # MinIO（S3 API 19000 / 控制台 19001；镜像走 quay，见 compose 注释）
export AIFLOW_STORAGE_ENABLED=true AIFLOW_STORAGE_ACCESS_KEY=aiflow AIFLOW_STORAGE_SECRET_KEY=aiflow-minio

curl -F "file=@samples/kb/员工手册.pdf" "http://localhost:8080/api/v1/kbs/demo/documents/upload"
# -> {"documentId":"...","status":"INDEXED","objectKey":"kb/demo/documents/<id>/员工手册.pdf","objectBytes":184233}

curl -OJ "http://localhost:8080/api/v1/kbs/demo/documents/<documentId>/original"
# -> 原件字节流（Content-Type: application/pdf，X-Object-Key 回显键名）
```

`timeout` 的接线契约见 [对象存储超时设计](docs/storage-timeout-design.md)：有效范围 `1ms` 到 `2147483647ms`，有效值的毫秒以下尾数截去，小于 `1ms` 的值直接拒绝。单次 HTTP call 的总时限包含响应体读取，持续但缓慢的下载也可能超时；region 查询、建桶和分片等多个请求各自计时，整个对象操作可能超过该值。实现与验收见 [对象存储超时验收](docs/storage-timeout-validation.md)。

三条刻意设计：

- **留存失败不阻断入库**：写不进去只记 WARN、`objectKey` 留空，文档照旧 `INDEXED`。对象存储是可选能力，
  把它做成硬依赖的代价是「对象存储抖一下，用户连知识库都用不了」。
- **"没有原件"分三种口径**：能力没开 -> 503；这份没留住 -> 404（写明是留存失败）；对象存储里对象不见了 -> 404
  （写明是对象存储侧的问题）。三种情况的排障动作完全不同，不能都回一句"没有"。
- **键按 kb / document 分层**：`kb/{kbId}/documents/{documentId}/{fileName}`，按前缀就能清理整个知识库；
  文件名里的路径分隔符会被净化，免得把上层目录语义写进键里。

接口级验证（`DocumentOriginalStorageIntegrationTest`，内存桩对象存储，全程离线）：上传 -> 响应带 `objectKey` ->
下载回来**与上传的原件逐字节相同**、类型与长度一致；把桩切成"写不进去"后，入库仍 `INDEXED`、`objectKey` 为
null、下载给出 404 并写明原因；纯文本入库没有原件（404）。
当前下载还覆盖元信息查询后对象消失（404）、存储故障（503）及禁用后历史原件（503）；非法 MIME 回退 `application/octet-stream`。留存/下载失败不再记录原始 SDK 正文或对象键。流式响应提交后的读取失败会中止传输，不能再改发 JSON 状态。
真实端到端（2026-09-22，真实 MinIO + 真实 PostgreSQL）：

- 上传 `digital-policy.pdf`（939B）-> `INDEXED`，`objectKey=kb/e2e-original/documents/<id>/digital-policy.pdf`；
  取回原件 `Content-Type: application/pdf`、`Content-Length: 939`，**上传前后 SHA256 相同**。
- MinIO 侧独立确认（不经过引擎）：`mc alias set e2e http://localhost:9000 aiflow <password>` 后
  `mc ls --recursive e2e/aiflow-originals` 能看到同一 key、同一个 939B 对象。
- **降级**：`docker stop aiflow-minio` 后上传一份 Markdown -> 仍 `INDEXED`、`objectKey=null`，日志留
  `原件留存失败（入库不受影响，object_key 留空）: ... Failed to connect to localhost/127.0.0.1:19000`；
  这条文档取原件 -> 404「这份文档没有留存原件（入库时留存失败，看那时的 WARN 日志）」。
- **恢复**：`docker start aiflow-minio` 后再传一份 -> `objectKey` 非空、`Content-Type: text/markdown`、
  `Content-Disposition` 带 RFC 5987 的 `filename*`，下载与上传 SHA256 相同 —— 上一次是**瞬态失败**而非永久退化
  （引擎没有把对象存储标成不可用，连接恢复即重新留存）。

### 内容安全审核（输入 + 输出同规则）

```yaml
aiflow:
  safety:
    enabled: true
    max-input-chars: 4000        # 超长直接拒，不静默截断
    max-output-chars: 8000
    rejected-reply: "抱歉，这条内容没有通过内容安全审核，已为你拦截。"
    keywords: [示例词]            # 真实词表请用 keywords-file（一行一个，# 开头为注释）
    keywords-file: ${AIFLOW_SAFETY_KEYWORDS_FILE:}
    patterns: ["忽略(之前|上面)(的)?(所有)?指令"]
```

- 输入侧拦在**入库与排队之前**：违规消息不落库、不占会话队列、不产生任何模型调用，返回 400 与命中的**类别**。
- 输出侧拦在**返回给用户之前**：整条回复换成预设话术，run 标记 `DEGRADED`，节点输出带 `blocked=true` 与 `blockCategory`。
- 只回类别不回命中词：回显命中词等于把绕过词表的线索送给调用方；命中的具体项写 DEBUG 日志。
- 规则层不是模型层审核的替代品，两者互补：这一层可解释、零延迟、可回归，先挡掉明确不该进出的内容。

#### 流式增量审核（输出侧跟着 SSE 走）

输出审核只作用于完整回复有个硬伤：SSE 片段在生成过程中早就推送出去了 ——「最终答案是预设话术」与「用户已经读到违规片段」可以同时成立。

```yaml
aiflow:
  safety:
    streaming:
      enabled: true
      hold-back-chars: 32        # 保留窗口的下限：最近的 N 个字符先留在缓冲里不推送
      auto-window: true          # 窗口自动抬到「hold-back-chars 与最长敏感词长度」的较大值
      max-auto-window: 200       # 自动抬高的上限（挡住「有人往词表里粘了一整段话」这类病态词条）
```

- 做法是**保留窗口**：最近「生效窗口」个字符先扣住，下一批片段到达时连同窗口一起过规则，通过后才放行窗口之前的部分。
- 生效窗口 = `max(hold-back-chars, 词表里最长敏感词长度)`（`auto-window: false` 可关掉抬高，做实验用），上限 `max-auto-window`。词表里加一个比窗口长的词等于留了一条「先推送、后拦截」的缝，自动抬高就是按词表实际情况把缝补上 —— 代价只是多延迟几个字符。
- 窗口重叠保证不漏：每次检查的是「上一段末尾 (窗口-1) 个字符 + 本段候选」，所以任何长度不超过窗口的命中串，只要有一个字符落在本段，就必然完整出现在本次检查范围内；命中后整个候选段（含命中串）都不发出。
- **承诺**：长度 ≤ 生效窗口的命中，**整词**绝不会先推送再拦截；只有病态超长（词长 > `max-auto-window`，默认 200）才退回最终整段审核兜底，装配时打 WARN 明说（不静默）。放行按冲刷边界走、不按词边界，所以命中串的**前缀**可能已经流出 —— 见下面第 2 轮实测里推送出去的那个「杭」字。
- 代价是最多延迟窗口大小的输出（逐字流下是几十毫秒），换审核真正生效。窗口恰好等于回复长度时表现为「一个字都不推，最后整条拦下」—— 短回复属正常现象。
- 关闭 `streaming.enabled` 即退化为纯透传，此时只有完整回复的最终审核生效。

三轮实测（2026-09-20，真实 `deepseek-flash`）：

```bash
# 1) 窗口 = 默认 32，回复会命中「杭州」-> 一个字都没推出去
curl.exe -N "http://localhost:8080/api/v1/runs/606cf721-ca71-4279-963a-f5be4e1044c0/events"
# -> status=DEGRADED，output 为预设话术；事件流 0 条 DELTA（DELTA 是「从未推送过违规内容」的硬证据）
#    应用日志：内容审核命中关键词（增量）: 杭州

# 2) 窗口 = 6（把窗口收到与词长同量级，看推送边界），回复命中「杭州」
curl.exe -N "http://localhost:8080/api/v1/runs/2ed79773-1c34-4551-b4ce-493fc4a81828/events"
# -> status=DEGRADED，output 为预设话术；DELTA 只有 1 条、文本为「杭」
#    推送出去的内容不含「杭州」二字（前缀泄漏属窗口语义的设计内行为）

# 3) 窗口 = 默认 32，回复不命中词表 -> 增量层必须「不改一个字节」
curl.exe -N "http://localhost:8080/api/v1/runs/7ce1bb13-cafa-424a-9994-b1693de72996/events"
# -> status=SUCCEEDED；72 条 DELTA 拼接 == 最终 reply（133 字，逐字相同）
```

第 3 轮是这层最容易出错的地方：保留窗口意味着**结尾最后一段要靠 `finish()` 补推**，漏掉就是「回复莫名少一截」。
实测拼接后与终态回复完全一致，说明不丢字也不重字。另有一次窗口 32、回复较短的 run 同样 0 条 DELTA —— 整条被扣住判命中。
输入侧审核不受影响，仍在排模型之前把违规消息挡成 400。

### 每用户 token 额度

```bash
curl http://localhost:8080/api/v1/budget/users/<userId>
# -> {"used":18,"limit":200000,"ratio":0.00009,"status":"NORMAL","resetAt":"2026-09-20T16:00:00Z","store":"memory"}
```

```yaml
aiflow:
  budget:
    enabled: true
    per-user-daily-tokens: 200000   # <=0 表示不限
    soft-threshold-ratio: 0.8       # 用量到这个比例后，本用户的新请求优先便宜档位
    reset-zone: Asia/Shanghai       # 跨天重置口径写死时区，别跟着机器漂
    store: memory                   # 单实例用 memory；多实例换 redis
  provider:
    cheap-tiers: [local, mock]      # 「便宜」由配置说了算，不由代码猜
```

- 判定在调用前、扣减在调用后：软阈值**换档**（降级但可用），硬上限直接给预设话术（`DEGRADED`，不是 500）。
- 最后一个请求可能略微超出上限：为了精确而把已经生成的回答丢掉，比超支几十个 token 更不划算。
- `store=redis` 时计数跨实例共享；Redis 不可用会退化为进程内计数并打一条带节流的 WARN ——
  额度是成本控制而不是安全边界，不该因为它把整条对话链路带崩（多实例下额度会被放大，这一点写在配置注释与架构文档里）。

### 人工审批：挂起 / 恢复（原 M5 提前交付）

运行跑到 `approval` 节点会**挂起**（不等线程、不占会话锁），等人决策或超时收口后从断点继续。
契约（状态机 / 表 / 接口 / 超时升级 / 不做清单）见 `docs/approval-node-design.md`。

```bash
# 1) 注册一个带审批节点的工作流（assignees 必填，其余参数有默认值）
curl -s -X POST localhost:8080/api/v1/workflows -H "Content-Type: application/json" -d '{
  "id":"approval-demo","name":"approval-demo","version":1,
  "nodes":[
    {"id":"start","type":"start","name":"开始"},
    {"id":"approve","type":"approval","name":"人工审批","params":{
      "title":"审批申请：${input}","assignees":"reviewer-a","waitSeconds":600,
      "onTimeout":"reject","maxLevel":1,"onExhausted":"reject","onReject":"fallback",
      "fallback":"很抱歉，您的申请未通过审批。"}},
    {"id":"end","type":"end","name":"结束","params":{"outputs":{"fallback":"fallback","approvalStatus":"approvalStatus","approvalBy":"approvalBy"}}}
  ],
  "edges":[{"from":"start","to":"approve"},{"from":"approve","to":"end"}]}'

# 2) 建会话（绑定这个工作流）并发一条消息 -> 运行挂起在 approve
#    SSE 事件流：NODE_SUSPENDED -> RUN_WAITING -> APPROVAL_REQUESTED

# 3) 待办列表（按人过滤；第一版 status 只支持 PENDING）
curl -s "localhost:8080/api/v1/approvals?assignee=reviewer-a"
# -> {"count":1,...,"approvals":[{"approvalId":"...","runId":"...","level":1,"status":"PENDING","dueAt":"...","onTimeout":"REJECT"}]}

# 4) 决策：decidedBy 必填；名单外的人 403、重复决策 409，且都不会消耗这次审批
curl -s -X POST localhost:8080/api/v1/approvals/<approvalId>/decision -H "Content-Type: application/json" \
  -d '{"decision":"APPROVE","decidedBy":"reviewer-a","comment":"同意"}'

# 5) 运行从断点恢复（不重放前面的节点）
curl -s localhost:8080/api/v1/runs/<runId>
# -> {"status":"SUCCEEDED","steps":3,"output":{"approvalStatus":"APPROVED","approvalBy":"reviewer-a"}}

# 6) 运行视角的审批链（升级过的每一层都在）；挂起期间取消会**同时**把待办置 CANCELED
curl -s localhost:8080/api/v1/runs/<runId>/approvals
curl -s -X POST "localhost:8080/api/v1/runs/<runId>/cancel?actor=ops"
```

行为要点：超时按 `onTimeout` 收口（放行 / 打回 / 升级一层，到顶按 `onExhausted`）；「谁批的」写在 `decidedBy` / `actor` 里 ——
**开启鉴权后它就是可验证身份，关着（默认）才是自报字段**，此时名单校验只防误操作（见下面的「鉴权最小切片」）；
挂起期间同会话的下一条消息**可以照常跑完**（有意的取舍，
端到端证据见 `script/verify-approval-flow.ps1`：实测第二条消息 354.9ms 自己跑到挂起）。

### 审批通知：log + webhook

状态机的每一次转移发一条通知（`REQUESTED` / `DECIDED` / `EXPIRED`（升级）/ `CANCELED`），
**在事务提交后**异步投递（单线程 + 有界队列，满则丢最老并计数），失败只计数不回滚审批。

```bash
# 默认只开 log 渠道（logger aiflow.approval.notify）；webhook 默认关
$env:AIFLOW_APPROVAL_WEBHOOK_ENABLED='true'
$env:AIFLOW_APPROVAL_WEBHOOK_URL='https://your-receiver.example.com/aiflow/approval'
$env:AIFLOW_APPROVAL_WEBHOOK_SECRET='<只有你和接收方知道>'   # 可选；给了就带 HMAC 签名头

# 配了没生效 / 投出去没 / 丢了多少，都在这条接口上
curl -s localhost:8080/api/v1/approvals/notify
# -> {"enabled":true,"queueDepth":0,"sent":6,"failed":0,"dropped":0,"lastError":null,
#     "channels":[{"channel":"log","enabled":true},{"channel":"webhook","enabled":true,
#                  "target":"https://your-receiver.example.com:443","signed":true}]}
```

- webhook **出网走与 HTTP 工具同一份护栏**（`aiflow.tools.allow-hosts` / `allow-private-hosts` / `allow-ports`）：
  审批节点常由业务方在低代码页面上自己拖出来，不设闸就等于开了个「往任意地址发 POST」的口子。
  过不了护栏时该渠道标记为未就绪（原因写在状态接口的 `blocked` 里），**不影响审批本身**。
- 载荷是事发那一刻的快照（`assignees` / `dueAt` / `title` …），接收方不用回查引擎就能渲染待办卡片；
  每条带 `dedupeKey`（`approvalId|event|level`），重放不会变成两张卡片。
- 签名：`X-AIFlow-Signature: sha256=hex(HMAC-SHA256(secret, body))`；secret 只从环境变量来，不进库、不进日志。
- 刻意不做重试与死信：需要可靠投递时按 `dedupeKey` 拉审批链（`approvals` / `approval_events` 是持久的），
  比让引擎扛重试更简单。契约见 `docs/approval-node-design.md` §12。

### 鉴权最小切片：审批决策 / 取消运行不再自报身份（P3-3）

`POST /api/v1/approvals/{id}/decision` 与 `POST /api/v1/runs/{id}/cancel` 标了 `@RequireIdentity`：
**带令牌时以令牌身份为准** —— body 里的 `decidedBy` / query 里的 `actor` 与令牌身份不一致直接 400（不静默忽略，
否则调用方会以为自报生效了），省略则用令牌身份。于是「谁说自己是 reviewer-a 谁就能批」这个口子在开启后关掉了。
完整契约（含「为什么是静态令牌不是 JWT」「明确不做」）见 `docs/auth-design.md`。

```powershell
# 打开鉴权：三个环境变量（令牌**值**只来自环境变量，yml 里只有变量名；默认关）
$env:AIFLOW_AUTH_ENABLED='true'
$env:AIFLOW_AUTH_TOKEN_REVIEWER_A='<你定的串>'
$env:AIFLOW_AUTH_TOKEN_ADMIN='<另一个串>'

# 自检：带着令牌问「你认出我了没」（匿名也 200，只是 authenticated=false）
curl.exe -s localhost:8080/api/v1/auth/whoami -H "Authorization: Bearer <串>"
# -> {"data":{"enabled":true,"authenticated":true,"subject":{"id":"reviewer-a","displayName":"审批人 A"}}}

# 决策：带令牌就不用再自报 decidedBy；令牌不认 -> 401（不降级为匿名），不在名单 -> 403
curl.exe -s -X POST localhost:8080/api/v1/approvals/<approvalId>/decision \
  -H "Authorization: Bearer <串>" -H "Content-Type: application/json" -d '{"decision":"APPROVE"}'
```

- 覆盖 `/api/**` 与 `/actuator/**`（`/actuator/health` 例外：编排探活要免鉴权）；静态资源不拦，数据都在 API 后面。
- 装配期校验不静默：开了鉴权却取不到令牌变量、`subjects` 为空、两个主体共用令牌 -> **启动失败**（只报变量名，不打值）。
- **默认关**：本地零配置照常可用（`decidedBy` 仍是自报），启动打 WARN 明说名单不是安全边界；关闭时
  `@RequireIdentity` **不生效** —— 注解声明「这个接口需要身份」，是否需要身份由部署开关决定。
- token 比较用 `MessageDigest.isEqual`（常量时间）且不提前返回；大小写与空白不宽容。

实测（2026-09-22）：真实栈 `script/verify-auth-flow.ps1` PASS 10/10（匿名 401 / 坏令牌 401 / 非名单 403 /
自报冲突 400 / 令牌身份 200 且落库 `decidedBy=reviewer-a`）；控制台在浏览器里匿名点批准报 40100、填令牌后
批准成功且链上决策人是主体 id；全量 `mvn test` 436 条 0 失败。

### 会话记忆：窗口 + 滚动摘要（M4-1）

`llm.chat` / `llm.vision` 发出的不再是一条 `user(prompt)`，而是引擎在装配期拼出来的
`[system(摘要)] + 最近 N 条历史 + 本轮输入` —— 同一会话的第二轮开始能听懂「那它呢」「换成图片版」这类指代。

- **窗口**（默认开）：最近 8 条 / 4000 字符，从最新往回留，超预算丢最老的（单条超预算**截断**而不是整条丢）。
  节点可覆盖：`history` 传 `none` 表示不要历史、传数字表示只要最近几条，`includeSummary: false` 表示不要摘要。
- **滚动摘要**（默认关，要调模型）：攒够 `summary.chunk-size` 条（默认 6）旧消息，在运行收口之后异步折进摘要；
  原消息不删、只推进前缀计数 —— 所以摘要是可重算的派生数据，`rebuild` 敢直接清。

  ```powershell
  $env:AIFLOW_MEMORY_SUMMARY_ENABLED='true'
  $env:AIFLOW_MEMORY_WINDOW_SIZE='8'          # 观察时可调小，例如 4
  $env:AIFLOW_MEMORY_SUMMARY_CHUNK_SIZE='6'   # 观察时可调小，例如 2
  ```

- **看得见**：`GET /api/v1/sessions/{id}/memory`（折叠到哪 / token 花了多少 / 失败了几次 / `readFailures`）、
  `GET .../memory/preview?input=...`（把这一轮装配结果摊开，逐条 role / chars / 正文截断 500 字）、
  `POST .../memory/rebuild`（清摘要与计数重算，token 累计保留 —— 那是已经花掉的钱）。
- **不影响主链路**：读失败降级成「没有记忆」并计入 `readFailures`；摘要失败只记账（`attempts` / `lastError`），连续失败到上限停用该会话；
  折叠用条件更新决出单赢家（多实例同时折叠只有一个生效）；摘要 token **计入用户额度**。

真实栈验证（要一个**摘要开着**的实例，且把窗口与 chunk 调小，否则要聊很多轮才看得见折叠）：

```powershell
$env:AIFLOW_MEMORY_ENABLED='true'; $env:AIFLOW_MEMORY_WINDOW_SIZE='4'
$env:AIFLOW_MEMORY_SUMMARY_ENABLED='true'; $env:AIFLOW_MEMORY_SUMMARY_CHUNK_SIZE='2'
powershell -NoProfile -ExecutionPolicy Bypass -File script\verify-memory-flow.ps1 -BaseUrl http://localhost:8080 -WorkflowName basic-chat -WindowSize 4 -SummaryChunk 2 -OutFile target/memory-flow-verify.json
```

2026-09-23 实测（临时第二个实例 + PG 15432 + mock 档）：**20/20 PASS**，三轮回合后 `summarizedCount=2`、`tokens=200`、
装配 = system 摘要 + 窗口 4 条 + 本轮输入 = 6 条；`rebuild` 清摘要保留 token；未知会话 404。
脚本打**摘要关**的实例时摘要相关断言必然失败 —— 这是刻意的：分不清「没折」和「不该折」的脚本没有价值。

## 云端档接入（OpenAI 兼容网关，不下载模型）

云端档只要一个 OpenAI 兼容地址 + 密钥；密钥只走环境变量，不进仓库、不进日志。
推荐网关（模型名以各自控制台的模型列表为准）：

| 网关 | base-url | embedding | 备注 |
| --- | --- | --- | --- |
| 硅基流动 SiliconFlow | `https://api.siliconflow.cn/v1` | `BAAI/bge-m3`（1024 维，有免费额度） | 与本地 Ollama 用的是同一个模型，口径一致 |
| 阿里云百炼（兼容模式） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `text-embedding-v3`（默认 1024 维） | VL 用 `qwen-vl-*` 系列 |
| 火山方舟 Ark | `https://ark.cn-beijing.volces.com/api/v3` | 有 `doubao-embedding-*`（维度需核对 1024） | 2026-09-22 实测 `doubao-seed-2-1-pro-260628`：chat + vision 都通 |
| 阿里云百炼专属网关 | `https://<你的网关>/compatible-mode/v1` | — | 2026-09-22 首测 401（密钥待换），端点已配好、开一个环境变量即可启用 |

> 维度硬约束：`document_chunks.embedding` 是 `vector(1024)`。默认输出 2048 维的模型（例如智谱 `embedding-3`）
> 会被明确判为「维度不一致」，而不是静默算错 —— 要么换模型，要么改迁移（schema 变更，先商量）。

```powershell
# 对话 + 多模态档
$env:AIFLOW_CLOUD_LLM_ENABLED='true'
$env:AIFLOW_CLOUD_LLM_BASE_URL='https://api.siliconflow.cn/v1'
$env:AIFLOW_CLOUD_LLM_MODEL='<控制台里的 VL 模型名>'
$env:AIFLOW_CLOUD_LLM_API_KEY='sk-...'

# 向量化档（不配则继续用 mock / 本地档）
$env:AIFLOW_EMBEDDING_CLOUD_ENABLED='true'
$env:AIFLOW_EMBEDDING_BASE_URL='https://api.siliconflow.cn/v1'
$env:AIFLOW_EMBEDDING_API_KEY='sk-...'
```

```powershell
# 1) 真实阈值重校准（不需要本地模型；报告在 target\eval\rag-report-http-embedding-*.json）
$env:AIFLOW_EVAL_EMBEDDING_API_KEY='sk-...'
powershell -File script\run-cloud-embedding-calibration.ps1 -BaseUrl https://api.siliconflow.cn/v1 -Model BAAI/bge-m3
# 把报告建议的阈值写回 application.yml 的 aiflow.retrieval.embedding.min-score，并按 docs/quality-plan.md §2.1.1 记录

# 2) 重建索引：旧向量是 mock 或别的模型算的，跨向量空间的余弦没有意义
curl -X POST localhost:8080/api/v1/kbs/demo/reindex

# 3) 验证：chat / vision 的 activeTier 应变成 cloud
curl -s localhost:8080/api/v1/capabilities
```

### 已接入的真实云端端点（2026-09-22 实测）

同一个档位可以挂多个端点：`ark` / `qwen` / `deepseek` / `cloud` 各自是独立端点，档位名同为 `cloud`，
**同档位内按配置里的声明顺序尝试**（谁写在前面谁是主、后面的当备）。关掉主端点后同档位的下一个会自动接管 ——
实测把 `AIFLOW_QWEN_ENABLED` 置 false 再发一次对话，`node_runs.output` 变成
`provider=deepseek, model=deepseek-flash`，回复正常、`skippedTiers=[local]`。

```yaml
aiflow:
  provider:
    endpoints:
      ark:
        enabled: ${AIFLOW_ARK_ENABLED:false}     # 默认关：没密钥的机器不该每 15s 往第三方探活一次
        tier: cloud
        base-url: https://ark.cn-beijing.volces.com/api/v3
        model: doubao-seed-2-1-pro-260628        # 收 text|image|video -> text
        api-key: ${AIFLOW_ARK_API_KEY:}
        capabilities: [chat, vision]
        extra-body: '{"thinking":{"type":"disabled"}}'
        probe-timeout-ms: 6000
      qwen:
        enabled: ${AIFLOW_QWEN_ENABLED:false}
        tier: cloud
        base-url: https://<专属网关>/compatible-mode/v1
        model: qwen3.8-flash
        api-key: ${AIFLOW_QWEN_API_KEY:}
        extra-body: '{"thinking":{"type":"disabled"}}'
        probe-timeout-ms: 6000
      deepseek:
        enabled: ${AIFLOW_DEEPSEEK_ENABLED:false}
        tier: cloud
        base-url: https://api.deepseek.com/v1
        model: deepseek-flash
        api-key: ${AIFLOW_DEEPSEEK_API_KEY:}
        extra-body: '{"thinking":{"type":"disabled"}}'
```

```powershell
$env:AIFLOW_ARK_ENABLED='true'
$env:AIFLOW_ARK_API_KEY='ark-...'    # 只在本机设环境变量：不进仓库、不进日志、不进对话
```

三件实测踩出来的事，接别的厂商时还会遇到：

1. **推理模型的思考链默认开着，会把流式对话拖过节点超时。** 同一个问题（「用一句话说明幂等」）：开着思考
   100 秒还在吐 `reasoning_content`（节点 60s 超时被中断，降级到 mock），关掉 5 秒出答案。`extra-body`
   就是给这类厂商私有开关留的口子（方舟的 `thinking`、DashScope 的 `enable_thinking` 都走它），
   不必改 provider 代码；协议主干 `model` / `messages` / `stream` 不允许覆盖，启动期直接拒绝。
   `extra-body` 非空时必须是单个 JSON 对象，顶层字段不能为 null；格式错误只给固定说明，不回显配置正文或原始解析异常。
   详细边界见 [扩展参数解析设计](docs/endpoint-extra-body-design.md) 和 [离线验收](docs/endpoint-extra-body-validation.md)。
2. **探活打的 `GET /models` 有多大由厂商决定。** 方舟的清单有几百 KB，1.5s 的全局超时会让档位在 up/down
   之间反复跳 —— 实测有一次视觉请求正好落在 down 窗口里，被判「不可用」跳过、降级到 mock，而那一档其实好好的。
   端点级 `probe-timeout-ms` 就是解这个的（方舟给 6s）。
3. **`order` 里写的是档位名，不是端点 id。** 所以新端点按 `cloud` 的优先级参与路由，同档位内部保持声明顺序。
   这条以前并不成立（按 id 匹配，新端点会掉到 mock 后面），已修并用 `ProviderRouterTierOrderTest` 钉住。

实测证据（`node_runs.output`，真实库）：对话 run `provider=ark`、`model=doubao-seed-2-1-pro-260628`、
184 tokens、`skippedTiers=[local]`、端到端 9s；视觉 run `provider=ark`、`imageCount=1` —— 用一张自合成的
七段数码管图片问「图里显示的数字是多少」，答「42」（用合成图是为了不给第三方送真实图片）。
另外两家（2026-09-22 当日新增）：百炼 `qwen3.8-flash` 跑通对话 run `5b60b9a4`（`provider=qwen`、
59 tokens、答「3 乘 7 的结果是 21」），DeepSeek `deepseek-flash` 跑通 run `1940e191`（`provider=deepseek`）。
**这两家也都是推理模型**：DeepSeek 不关思考时 16 个 token 全被 `reasoning_content` 吃掉、`content` 是空串，
关掉后 1 个 token 出结果；`qwen3.8-flash` 开思考 `completion=40`、关思考 `completion=8`（两种写法
`thinking:{type:disabled}` 与 `enable_thinking:false` 都认，配置里统一用前者，与 ark 端点同一种拼法）。

云端档是「先跑起来、再慢慢补本地」的推荐路线；本地档的价值在断网兜底，两者共用同一份 OpenAI 兼容契约。

### 云端知识库（厂商检索 API）

云端知识库与本地检索共用同一条后处理链：厂商的分数先按 `score-min` / `score-max` 线性归一化到 `[0,1]`
（`LinearRangeScoreNormalizer`），再走阈值过滤 -> 断崖 top-k -> MMR -> 相邻合并。**归一化刻意放在适配器之外**：
厂商分数是它自己的量纲（0~1、0~100、有的还是"越小越像"的距离），把它交给配置，本地这条链就只认识一种口径 ——
「相似度太低的丢弃」「召回太多时按相似度砍掉差的」在云端档才有可比性。

⚠️ 联调前先探形：`HttpKnowledgeBaseProvider` 吃的是**自定义契约**，不是任何厂商的协议：

```text
POST {base-url}/retrieve   {"dataset_id": "...", "query": "...", "top_k": 3}
-> {"chunks": [{"id": "...", "document_id": "...", "title": "...", "content": "...", "score": 0.83, ...}]}
```

所以写适配器之前，先用探形脚本对真实厂商跑一发，把它的请求 / 响应形状抓下来 —— 映射关系要抄，不要猜：

```powershell
$env:AIFLOW_CLOUD_KB_API_KEY='...'   # 只在本机设环境变量，别贴进对话、别进仓库
powershell -File script\probe-cloud-kb.ps1 -BaseUrl https://<厂商网关> -Path /<检索路径> `
    -Body '{"question":"{query}","dataset_ids":["{dataset}"],"top_k":{topK}}'
```

输出三块：HTTP 结果、原始响应（截断）、结构摘要（数组路径 / 首元素字段与取值 / **疑似分数字段跨全部元素的范围**）。
最后一块直接对应 `score-field` 与 `score-min` / `score-max` 的取值；鉴权形态用 `-AuthHeader` / `-AuthPrefix` 覆盖，
无鉴权端点用 `-NoAuth`；不传 `-Body` 则按上面那套自定义契约试一发。

联调需要三件东西（都不含密钥本体）：① 厂商 base-url 与检索路径；② 鉴权形态（Bearer / 自定义头）；
③ 协议文档或探形脚本的输出。拿到之后按厂商协议写字段映射，再用
`POST /api/v1/kbs/{kbId}/retrieve`（`sources: ["cloud"]`）复核归一化后的分数与截断行为。

### 本地检索的两条候选集路径（ANN / 精确，2026-09-22 实测）

`embedding-store=pgvector` 时本地检索有两条取候选的路，**产出同口径**（都是 [-1,1] 的原始余弦），
后面的阈值 / 断崖 top-k / MMR / 相邻合并完全共用 —— 换路径只改候选集怎么来，不改语义：

| | ANN（默认） | 精确 |
|---|---|---|
| 取数 | `order by embedding <=> cast(? as vector) limit N`，走 `idx_chunks_embedding`（HNSW） | 把该 kb 该模型的全部向量读进 JVM，逐条算余弦 |
| 何时用 | `ann.enabled=true` 且存储支持 ANN | `ann.enabled=false`、`embedding-store=memory`、ANN 抛错，或 **ANN 查空** |

```yaml
aiflow:
  retrieval:
    ann:
      enabled: true                   # 关掉即回到精确余弦：小 kb 够用，大 kb 每次要搬几十 MB 向量
      candidates: 48                  # 候选宽度；配到小于 max-k 会自动按 max-k 生效并打 WARN
      ef-search: 100                  # HNSW 查询期搜索宽度（1..1000）：越大越准越慢
      iterative-scan: relaxed_order   # pgvector 0.8+：带 where 过滤时继续下扫，避免「明明有却查不到」
```

**为什么 ANN 查空要回退精确路径**：带 `where` 过滤的 HNSW 在 pgvector 0.8 之前是后过滤（先扫满 ef_search 条再筛
kb / model），筛完可能是空的 —— 那不代表真的没命中。空结果退回精确路径，要么拿到真实命中，要么照旧抛
「索引是别的模型建的」那条显式报错。代价是这种情形下多一次全量读，所以按 DEBUG 记日志。

```powershell
# 两条路径对照：先在 AIFLOW_ANN_ENABLED=true 下跑 on，停机换 false 再跑 off，比对两个 JSON
powershell -File script\verify-ann-retrieval.ps1 -Label on  -MinScore 0
powershell -File script\verify-ann-retrieval.ps1 -Label off -MinScore 0
# 规模基准（先按 script\sql\bench-ann-needles.sql 造 5000 条切片，含 200 个已知最近邻）
powershell -File script\bench-ann-scale.ps1
```

> 基准数据落在独立 kb `e2e-ann-bench`（5000 行、约 100MB 向量文本），要清掉跑：
> `delete from document_chunks where kb_id='e2e-ann-bench'; delete from documents where kb_id='e2e-ann-bench';`

**实测（本机 pgvector 0.8.6，2026-09-22）**：

- 真实语料（8 篇样本文档 / 11 条切片，5 个查询）：ANN 与精确**逐条一致**（chunkId 与 rawScore 全同）。
- 规模基准（5000 条切片 + 200 个已知最近邻）：精确 p50 **878ms**、ANN p50 **30.5ms**（≈29×），
  两者 top-8 都等于标准答案（召回 8/8；首次调用含索引冷启动，p95 会高一些）。
  精确路径慢在把整库向量文本搬进 JVM（5000 × 1024 维 ≈ 100MB 文本 / 次查询），ANN 只回 48 条。
- `set local hnsw.ef_search` 确认真的发到了 PG：用越界值 1e9 跑会收到
  `1000000000 is outside the valid range for parameter "hnsw.ef_search" (1 .. 1000)`，
  引擎自动去掉调优参数重试且结果不变 —— 性能开关坏了不影响正确性。

## 换 embedding provider（接真实模型）

默认 `aiflow.retrieval.embedding.mode=mock`：确定性哈希，离线可跑，供单测与压测用。换真实模型只改配置，代码不动（云端 OpenAI 兼容网关 / 本地 vLLM / Ollama 同一份契约）：

```yaml
aiflow:
  retrieval:
    embedding:
      mode: http
      base-url: http://localhost:11434/v1   # Ollama；vLLM 与云厂商网关同理
      model: bge-m3                          # 必须输出 1024 维，与 vector(1024) 列一致
      min-score: 0.50                        # 跟着向量模型校准，不跟着检索算法；见 docs/quality-plan.md §2.1.1
      # api-key 走环境变量，别写进仓库、别写进日志
    # 全局 min-score 只服务 mock 量纲（0.35），真实向量档不要复用它
```

换 provider 后有两件事必须做，顺序不能反：

1. **重校准阈值**：`mvn test -Dtest=RagRealEmbeddingCalibrationTest '-Daiflow.eval.embedding.mode=http' ...`，报告落在 `target/eval/rag-report-<providerId>.json`。mock 量纲下校准出来的 0.15 直接搬过去必错 —— 真实模型对同义改写也给高分，阈值太低会把噪声全放进来。
2. **重建索引**：库里已有向量是旧 provider 算的，两个向量空间之间的余弦没有意义。V3 起 `document_chunks.embedding_model` 记录了来源，检索只认当前 provider 的向量；发现索引是别的模型建的会直接报错并给出重建命令：

   ```bash
   curl -X POST localhost:8080/api/v1/kbs/demo/reindex    # 控制台也有「重建索引」按钮
   ```
### 云端真实向量档（qwen3.7-text-embedding，2026-09-22 实测）

**不装 Ollama 也能跑真实向量**：百炼网关的 `qwen3.7-text-embedding` 原生返回 **1024 维**，与服务端
`vector(1024)` 列同维，不用改 schema、不用改代码，只换配置：

```powershell
$env:AIFLOW_EMBEDDING_MODE='http'
$env:AIFLOW_EMBEDDING_MODEL='qwen3.7-text-embedding'
$env:AIFLOW_EMBEDDING_QWEN_ENABLED='true'
$env:AIFLOW_EMBEDDING_LOCAL_ENABLED='false'   # 必须关：本机档跑的是 bge-m3，与 qwen 不是同一个模型
$env:AIFLOW_QWEN_API_KEY='sk-...'             # 复用对话档那把 key，同一个网关
$env:AIFLOW_EMBEDDING_MIN_SCORE='0.45'        # 校准值，见 docs/quality-plan.md §2.1.3（bge-m3 是 0.50，两个模型不通用）
```

⚠️ **两个档位不能同时开**：向量列只记模型名（`http-embedding:qwen3.7-text-embedding`），不记主机。
Ollama 的 bge-m3 也是 1024 维，一旦它顶替 qwen 出了向量，写进库的向量标着 qwen 的模型名却是 bge-m3 的数值 ——
同为 1024 维，**不会报错，只会静默算错相似度**。构建期的「同一条链必须同模型」校验只能看声明，
所以启动日志会把生效链条印出来（`embedding 链条（按优先级）：[qwen[cloud] http-embedding:qwen3.7-text-embedding @ https://...]`），
人一眼能看出哪台主机在伺候哪个模型。

验证脚本 `script/verify-realvec-retrieval.ps1`（自带合成语料，**不含任何用户数据**，只会把脚本里那 6 句话发给
embedding 服务）：6 篇合成制度文档 + 4 个**改写后与原文没有字面重叠**的查询，4/4 命中首位：

```text
出差住酒店最多能报多少钱  -> 差旅报销标准.md  raw=0.8059     （原文写的是「住宿费上限」）
我在家上班可以吗          -> 远程办公.md      raw=0.5351
辞职要提前多久说          -> 离职流程.md      raw=0.6743
买电脑找谁批              -> 设备申领.md      raw=0.5586
```

同一批查询在 mock（确定性哈希）量纲下只有 0.04~0.20 —— 这就是「真实模型对同义改写也给高分、余弦整体上移」
的直观对照，也是阈值必须跟着向量模型走的原因。

**ANN 与精确路径在真实向量下同样逐条一致**（同一 kb、同 4 个查询，chunkId 与 rawScore 全同），
说明前面的 ANN 结论不是稀疏 mock 向量特有的。

**阈值已按云端档单独校准（2026-09-22）：qwen3.7-text-embedding = 0.45**。黄金集（75 条查询）通过区间是 [0.40, 0.50]，
之所以不取上沿 0.50：拒答正确率在 0.40 就饱和到 1.0000，0.50 换不来拒答能力却多误拒 2 条可答问题。
第二语料（`samples/kb` 灌进新 kb `handbook-qwen`）给出两条硬边界 —— 噪声天花板 0.3709、必保命中下限 0.5769，
且**与本机 bge-m3 的边界不重合**（同题 bge-m3 正确片只有 0.5232），所以两个真实模型的阈值不能互换。
曲线、失败逐条明细与两家模型的排序病对照见 `docs/quality-plan.md` §2.1.3。

### 本机真实向量档（2026-09-20 实测）

```powershell
# 1) 一次性：同一份权重派生一个「不上 GPU」的别名
'FROM bge-m3
PARAMETER num_gpu 0' | Set-Content -Encoding utf8 $env:TEMP\bge-m3-cpu.Modelfile
ollama create bge-m3-cpu -f $env:TEMP\bge-m3-cpu.Modelfile

# 2) 切到真实向量档（三个环境变量；值同时在 application.yml 的注释里）
$env:AIFLOW_EMBEDDING_MODE='http'
$env:AIFLOW_EMBEDDING_MODEL='bge-m3-cpu'
$env:AIFLOW_EMBEDDING_MIN_SCORE='0.50'      # 校准值，见 docs/quality-plan.md §2.1.1
mvn spring-boot:run
# 启动日志会印：检索阈值 min-score=0.50（来源 embedding.min-score；mode=http model=bge-m3-cpu）

# 3) 重建索引：旧向量是 mock 算的，跨向量空间的余弦没有意义
curl -s -X POST localhost:8080/api/v1/kbs/demo/reindex
```

为什么用 `bge-m3-cpu`：本机（Quadro P1000 / Pascal + 16GB 内存）上 Ollama 的 Windows CUDA 路径会禁用 mmap，
把 1.1GB 模型整块读进内存；实测可用内存只剩 ~700MB 时 llama-server 直接以 `0xc0000005` 崩溃 ——
关掉 Flash Attention 一样崩（对照实验过），根因是内存不是 FA。CPU 路径走 mmap，稳定可用：
75 条查询的整轮校准 22.7 秒。**同一份权重 = 同一向量空间**，阈值可以沿用；但模型名变了，
`document_chunks.embedding_model` 记的是名字，切换后必须 `reindex`。

阈值为什么挂在 `embedding.min-score` 而不是全局 `min-score`：同一个数字在两种向量空间里代表完全不同的
严格程度 —— 实测「无关文本」在 bge-m3 下余弦仍有 0.36，而 0.35 正是 mock 量纲的默认阈值，
放在真实向量上等于「低相似度丢弃」失效。`mode=http` 却没配这个值时，启动会打 WARN 点名这件事。

### 本地对话档（2026-09-20 实测）

对话模型在同一台机器上也走不了 CUDA 路径（同样的 `0xc0000005`，根因见上一节），所以本地档同样需要一个 CPU 别名：

```powershell
'FROM qwen2.5:1.5b
PARAMETER num_gpu 0' | Set-Content -Encoding utf8 $env:TEMP\qwen2.5-cpu.Modelfile
ollama create qwen2.5-cpu -f $env:TEMP\qwen2.5-cpu.Modelfile

$env:AIFLOW_LOCAL_LLM_MODEL='qwen2.5-cpu'
mvn spring-boot:run
# 启动日志会印：provider endpoint local 已装配 base-url=http://localhost:11434/v1 model=qwen2.5-cpu capabilities=[chat]
```

实测：`basic-chat` 一发一答 3~20 秒（纯 CPU 推理，取决于模型是否已在内存里），节点快照 `provider=local`、无 `degradedFrom`。
注意一个坑：用原始名 `qwen2.5:1.5b` 时探活仍然报 `local -> up`（探活只核对模型在不在列表里），但真实调用会以 500
`llama-server process has terminated` 失败、随后降级到云端 —— **探活绿灯不等于能生成**，对话档要拿真实一发一答验证。

**质量取舍**：1.5B 的本地模型不适合「从资料里抽答案」这类活。实测同一个 `handbook-rag` 提问（「忘记打卡怎么办？」），检索很干净（引用 06 与 01 两条都对），本机 local 档却答「资料中没有」；同一问题走云端 `deepseek-flash` 能正确引用 [2] 作答。按这个证据，`aiflow.provider.order` 的默认值已改成 `[cloud, local, mock]`（质量优先）：本地档仍留在链上做兜底，断网时自动顶上；要离线优先就换回 `[local, cloud, mock]`（或设 `AIFLOW_PROVIDER_ORDER=local,cloud,mock`）。
这也解释了分级策略的取舍：**分级不是为了省钱，而是为了在能力退化时不中断服务** —— 质量差一档可以接受，服务不可用不能接受。

## 已知限制（按计划演进）

- 鉴权是最小切片（P3-3，`docs/auth-design.md`）：只有「审批决策 / 取消运行」两个接口要身份，其余业务接口仍按 userId 传参；**默认关**，关着时 `decidedBy` / `actor` 是自报字段、审批人名单**不是权限控制**；没有用户体系、没有 JWT 与角色权限（升级条件写在设计文档里），画像也没有 Subject→userId 归属隔离。多实例部署需显式开启 Redis 会话锁与 Redis Stream 事件模式；事件模式默认仍为 memory
- 检索的候选集默认走 pgvector ANN（HNSW），存储不支持或 ANN 查空时回退 Java 精确余弦；ANN 是**近似**，召回宽度靠 `ann.candidates` / `ann.ef-search` 调，调小前先跑 `script/bench-ann-scale.ps1` 看召回
- 黄金集回归跑在 mock embedding 上：它只对词面重叠敏感，域内「不可答」问题分不开，阈值是按它的量纲校准的。真实 embedding 的校准已完成，三个量纲各自成立：mock 0.35 / bge-m3 0.50 / qwen3.7-text-embedding 0.45，切换时必须换阈值（阈值挂在 `embedding.min-score`，不跟检索算法走）；阈值同时与语料结构相关，换语料后误拒成批出现时先看切片粒度（`docs/quality-plan.md` §2.1.1-2.1.2）
- 消息/run/调度任务已同事务提交，执行写入有租约围栏；PG 与 Redis 仍没有 outbox 原子提交，PG 快照为状态事实来源，Redis 事件可能缺失
- 分级降级、多模态节点、工具调用、内容安全审核（含流式增量审核）、每用户 token 额度均已落地（见上）；**短期记忆（M4-1）、长期记忆（M4-2）与用户画像（M4-3B）已交付**；Gatling、语音与声纹继续按 `docs/roadmap.md` 推进。
  记忆的已知边界：折叠去重（`inFlight`）、窗口装配与**抽取节流记账**都在**进程内**，跨实例只有「条件更新单赢家」这一层保证（见 `docs/memory-design.md` 与 `docs/architecture.md` §15-16），节流记账重启后退化成「从未抽取」（最坏多抽一次）；摘要与抽取质量都不做自动评估，用 `/memory/preview` 与 `/memories/preview` 人工看，**预览不回写 hits**（看一眼不算一次使用）；云端知识库真实厂商联调仍在 M3 待补；MCP 已支持 stdio、Streamable HTTP 与老式 SSE 双端点（含静态 Bearer / OAuth 动态刷新 / 热更新版本一致性）
- 额度只统计对话与多模态的 token（`prompt + completion`），不统计向量化调用；按自然日结算，最后一个请求可能略微超出上限
- 流式增量审核的承诺有明确边界：长度 ≤ **生效窗口**的命中，整词不会先推送再拦截（窗口默认按最长敏感词自动抬高，`auto-window` / `max-auto-window` 可关、可调上限）；只有词长超过上限（默认 200 字符）的命中才退回完整回复的最终审核兜底，装配时 WARN 不静默；放行按冲刷边界走、不按词边界，所以事件流里可能出现命中串的前几个字
- 工具调用默认全关；三个来源（HTTP / MCP / 技能库）与工具粒度授权、调用审计都已落地；MCP 的 stdio、Streamable HTTP 与老式 SSE 双端点（`type: "sse"`）均已支持；幂等缓存与循环检测都是**进程内**的，多实例部署下这两项护栏按实例计数

## 目录导览

```
src/main/java/com/aiflow/engine
├── api/           # Controller、DTO、应用服务、SSE 流
├── approval/      # 人工审批：待办 / 决策 / 超时扫描 / 恢复编排（不反向依赖引擎，避免装配期成环）
│   └── notify/    #   通知渠道 SPI 与分发（log / webhook，异步 + 有界队列 + 去重）
├── graph/         # 工作流定义与校验
├── node/          # 节点 SPI 与内置节点
├── run/           # 执行引擎、状态机、记录器接口
├── event/         # 运行事件总线
├── provider/      # 模型 provider 抽象与路由
├── audio/         # ASR/TTS 端口与格式校验（M4-3D）
├── retrieval/     # 检索 SPI、归一化、后处理链、提示词组装、mock 实现
├── ingest/        # 文档解析与切片
├── attachment/    # 图片附件落盘与元数据（sidecar）
├── tool/          # 工具注册表、SSRF 校验、调用护栏（幂等 / 循环 / 次数）
│   ├── mcp/       # MCP 客户端：stdio / Streamable HTTP 两种传输、配置导入（Codex / Claude）、服务端生命周期
│   └── skill/     # 本机技能库：扫 SKILL.md，暴露 skill.list / skill.read
├── safety/        # 内容安全规则层（长度 / 词表 / 注入模式）+ 流式增量审核
├── budget/        # 每用户 token 额度：账本、阈值判定、memory / redis 计数
├── integration/   # 对外 HTTP 适配（embedding、云端知识库、OpenAI 兼容 chat、audio）
├── memory/        # 会话记忆：窗口装配 + 滚动摘要（M4-1，契约见 docs/memory-design.md）
├── profile/       # 画像统计口径、显式偏好端口与白名单规则（M4-3B）
├── session/       # 会话队列与锁
├── persistence/   # JPA 实体、仓储、Recorder 实现
├── common/        # 响应包装、错误码、traceId
└── config/        # 配置绑定

samples/kb/        # 可复现的测试语料（8 篇文档，用于灌库后跑检索 / 问答验证，见该目录 README）
script/            # 本地环境脚本：装 Ollama、云端档校准、审批 / 鉴权 / 记忆 / 画像端到端（verify-*.ps1）
docs/              # 架构、路线图、质量方案（阈值校准记录）、契约文档（审批 / 鉴权 / 短期记忆 / 长期记忆 / 画像）
```

本轮 M4-3A 验收结果与限制见 [m4-3a-validation.md](docs/m4-3a-validation.md)。
M4-3B 验收结果与限制见 [m4-3b-validation.md](docs/m4-3b-validation.md)。
第一层离线 provider/HTTP 协议抽象与第二层合成黄金集已验收，见 [provider-layer1-validation.md](docs/provider-layer1-validation.md) 和 [provider-layer2-validation.md](docs/provider-layer2-validation.md)。第二层新增 63 条黄金场景与 9 条设施测试，全量 975 条、0 失败/错误、6 条按开关跳过；不代表真实模型质量或厂商联调已通过。
