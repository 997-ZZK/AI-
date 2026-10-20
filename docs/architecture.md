# 架构设计（AIFlowEngine）

> 2026-10-16：chat 流式响应超时与取消契约已按 `chat-stream-timeout-design.md` 验收：端点 `timeoutMs` 覆盖连接、响应头和响应体直到 `[DONE]`/兼容 EOF；超时只关闭当前流并按固定 provider 故障处理，读取中的用户中断抛 `RunCanceledException` 且不降低健康度。定向 109 条、全量 1134 条（0 失败/错误、6 跳过）及离线打包通过，见 `chat-stream-timeout-validation.md`。真实厂商流式行为仍后置。

> 2026-10-15：厂商扩展参数按 `endpoint-extra-body-design.md` 完成解析边界验收：启动期只接受单个对象，非法 JSON/null/尾随值给固定错误，不传播原始 Jackson 异常或配置正文；正常厂商字段和协议主干护栏保持。新增 22 条，定向 129 条、全量 1130 条（0 失败/错误、6 跳过）及离线打包通过，见 `endpoint-extra-body-validation.md`。

> 2026-10-15：对象存储超时修复按 `storage-timeout-design.md` 验收：将现有 timeout 接入 MinIO 的连接/读写与单次 HTTP call 时限，覆盖返回流后的下载读取。多个 SDK HTTP 请求各自计时，原件旁路与固定异常分类沿用原契约；定向 78 条、全量 1108 条（0 失败/错误、6 跳过）及离线打包通过，见 `storage-timeout-validation.md`。

> 2026-10-15：文档入库错误边界已按 `document-ingest-error-boundary.md` 收紧并验收：PDF/OCR、Multipart 和对象存储的原始异常正文、URL、凭证片段及异常链不进入 HTTP、`documents.error` 或应用错误诊断；下载区分未启用、对象不存在和存储故障，正常解析、OCR、切片和原件旁路语义不变。相关 59 条通过，全量 1089 条（0 失败/错误、6 跳过）及离线打包成功，见 `document-ingest-error-validation.md`。测试 JVM 临时目录位于项目可写路径，未关闭 JUnit 清理。

> 2026-10-14：RAG 检索错误边界已按 `rag-retrieval-error-boundary.md` 收紧：可插拔 embedding 与检索来源的异常只保留固定分类和来源标识，不传播 provider 原始 message、`toString()`、cause 或 suppressed 到检索异常、节点失败结果或日志；来源健康检查异常按部分降级处理，正常检索、阈值、融合与后处理语义不变。定向测试与离线打包结果见 `rag-retrieval-error-validation.md`。

> 2026-10-14：视觉 OCR 错误边界已按 `vision-ocr-error-boundary.md` 收紧并验收（`vision-ocr-error-validation.md`）：能力缺失保持固定提示；路由调用失败只暴露固定 OCR 调用失败分类，不传播上游正文、凭证、URL 或原始异常链到文档失败记录、HTTP 响应和运行快照。成功转写、`NO_TEXT`、页数闸与 OCR 开关语义不变。定向 39 条通过；扩大回归排除既有 Windows `@TempDir` 清理权限问题后 988 条通过；离线打包成功。

> 2026-10-14：Embedding 与探活错误边界已按 `embedding-probe-error-boundary.md` 收紧并验收（`embedding-probe-error-validation.md`）：定向 66 条、全量 1054 条（0 失败/错误、6 跳过）及离线打包通过。向量 HTTP 错误只报告状态及固定分类，网络/转换/构造异常不传播原始正文和异常链；缺失模型诊断不回显上游模型清单。向量结果、分级与探活判定、索引及阈值延续原契约，真实厂商验证仍后置。

> 2026-10-14：对话 HTTP 调用错误边界已按 `chat-error-boundary.md` 收紧并验收（`chat-error-validation.md`）：定向 33 条、全量 1019 条（0 失败/错误、6 跳过）及离线打包通过。非 2xx、流内错误与网络/请求构造/序列化/流读取失败只携带固定分类及 HTTP 状态，不把上游错误正文或原始异常链传播到健康状态、路由诊断和运行快照；非法 JSON 片段日志不回显原文。正常业务内容与 SSE 执行规则延续既有契约，真实厂商验收仍后置。

> 2026-10-14：运行回放与失败定位已验收，契约见 `run-replay-plan.md`，证据见 `run-replay-validation.md`。只读 `GET /api/v1/runs/{runId}/nodes` 分页与 `.../nodes/{nodeRunId}` 详情以 PostgreSQL `node_runs` 为事实来源；列表字段投影不读正文，详情校验所属 runId。控制台独立回放面板支持分页、刷新与按需详情，不改变实时 SSE、执行或审批语义。定向 30/30、全量 998 条（0 失败/错误、6 跳过）、真实 API 23/23、原演示 43/43、浏览器 22 项通过；两秒延迟的旧列表/详情响应未覆盖当前视图。

> 2026-10-14：本地使用与运维入口见 `local-ops-plan.md` / `local-ops-runbook.md` / `local-ops-validation.md`。统一脚本只绑定 127.0.0.1，以环境白名单和 classpath 配置固定 mock、禁用外部端点/探活/后台调度及数据库迁移；marker 描述绑定 JDK/JAR 哈希、PID、精确创建时间、完整参数和监听者。演示先核对此专用实例，再新增合成记录；不修改生产 API 或 Java 装配。双 PowerShell 门禁各 47/47、真实本机演示各 43/43、失败路径 11/11，复用第二层已验收包。

> 2026-10-14：第二层离线提示词/对话黄金集已验收，契约见 `provider-layer2-plan.md`，结果见 `provider-layer2-validation.md`：上下文/工作流/RAG 三组 24/21/18 条及设施 9 条通过，全量 975 条、0 失败/错误、6 条按开关跳过，离线打包成功。测试设施位于 `src/test/java/com/aiflow/engine/eval`，合成输入位于 `src/test/resources/eval/layer2-*-golden.json`；默认使用真实装配/执行代码与可控端口，不激活真实模型。
> 同切片修正资料提示边界：正文与 citation 显示标签都清洗连续角括号；标题/章节标签另扁平化换行和控制符，防止元数据伪造资料边界。引用输出元数据保持原值，结构护栏不替代真实模型注入评测。

> 2026-10-14：D2 节点/控制台已验收（`m4-3d-d2-validation.md`）。第一层离线 provider/HTTP 协议边界已完成，设计见 `provider-layer1-plan.md`、验收见 `provider-layer1-validation.md`（定向 85 条、全量 903 条回归通过，6 条外部设施/真实模型用例按开关跳过，离线打包成功）；新增映射与传输在 integration，默认不激活真实语音调用，不把本地协议桩当作厂商兼容证据。

> M4-3 演进方案见 `docs/m4-3-design.md`：A/B 已验收；C 的阶段性 mock 验证已完成；D0、D1 mock 音频链路已验收，具体见 `m4-3d-plan.md`、`m4-3d-d1-validation.md`。结果见 `m4-3a-validation.md`、`m4-3b-validation.md`、`m4-3c-validation.md`、`m4-3d-validation.md`。
> 下方 §17 为 A 的实施契约，未回填验证前不算交付。
> M4-3C 的 Redis reader 保持每 run 一个，阻塞 XREAD 连接已按 reader 生命周期复用；空读仍核验保留期，真实故障有限重试，最后退订释放连接。实现与阶段性验证见 `m4-3c-validation.md` 的 2026-10-12 收口记录。

## 1. 定位与原则

一句话：把 LLM 节点、工具调用、条件分支编排成**可运行、可观测、可降级**的工作流，并让引擎在离线与高并发下依然可控地工作（「从聊天到干活」）。

三条原则：
1. 引擎核心与基础设施解耦：通过 `RunRecorder`、`RunEventBus`、`SessionLock` 接口与 Spring Web / JPA 隔离，核心逻辑可纯单元测试。
2. 能力声明 + 分级降级：节点声明所需能力（chat / vision / asr / tts / embedding），找不到满足的 provider 时输出**预设提示**，而不是崩溃或静默失败。
3. Mock provider 是一级公民：联调、压测、故障演练都靠它，禁止对真实模型压测。

## 2. 分层

```
API 层            /api/v1: users, sessions, workflows, runs, tools, capabilities, attachments, audio（D1）, budget, kbs, SSE 事件流
应用服务层        SessionMessageService / RunCoordinator / WorkflowService / RunQueryService
会话与并发层      SessionDispatcher（按会话串行队列）+ SessionLock（Redis，可开关）
引擎层            GraphValidator -> ExecutionEngine（拓扑执行 / 重试 / 超时 / 步数预算 / 取消）
                  NodeRegistry（start / llm.chat / delay / end ...）
                  RunEventBus（单调 seq 事件流，SSE 消费，支持短暂回放）
Provider 与路由层 ChatProvider / ProviderRouter（M1）；EmbeddingProvider / KnowledgeBaseProvider（M2）；AsrProvider / TtsProvider（M4-3D，独立音频路由）；复杂度分级 + 降级矩阵 + 熔断（M3）
工具层            ToolRegistry（三来源同一张白名单）-> ToolAuthorizer（谁能调哪个工具）-> ToolCallNodeExecutor（HTTP / MCP / 技能库分发）-> ToolInvocationGuard（次数 / 循环 / 幂等）+ ToolAuditService（审计）
                  McpClient（传输抽象：stdio 子进程 / Streamable HTTP 单端点 / 老式 SSE 双端点）+ SsrfGuard（远程形态与 HTTP 工具同一套出网白名单）
解析层            DocumentParser（文本分诊）-> DocumentExtractor（PDF 文本层 / 图片 OCR）-> TextChunker（按页归属）
检索层            RetrievalProvider（local / cloud 同接口）-> 分数归一化 -> 阈值 -> 断崖 top-k -> MMR -> 相邻合并 -> 提示词组装
安全与额度层      ContentSafety（输入侧拦截 / 输出侧替换）-> StreamingSafetyGuard（SSE 增量审核：保留窗口）-> TokenBudgetService（软阈值换档 / 硬上限话术）
持久化层          JPA: users / sessions / messages / workflows / workflow_runs / node_runs / documents / document_chunks
基础设施          PostgreSQL 16 + pgvector（M2 启用向量列与 HNSW 索引）/ Redis 7 / MinIO（M3）
```

## 3. 关键设计决策（ADR 摘要）

1. **自研 DAG 引擎，不用 Flowable/Camunda**：BPMN 引擎面向人工审批流，模型重、侵入强；AI 工作流需要动态分支、流式输出、token 计量、能力路由，轻量自研更贴合，也是本项目的学习主体。
2. **SSE 而不是 WebSocket**：运行事件是单向流，SSE 天然支持断线重连（`Last-Event-ID`）、可读性好、对网关友好；双向实时音频（M5 以后）再评估 WebSocket。
3. **MVC + 虚拟线程，不引入 WebFlux**：SSE 是长连接但不是高 CPU 场景，Java 21 虚拟线程足以承载大量常连；避免双栈心智负担。注意 `synchronized` 会 pin 载体线程，热路径用 `ReentrantLock`（M4 压测时核查）。
4. **会话串行 = 队列优先，锁兜底**：同会话消息必须有序。长流式任务不能靠长持锁，用 `SessionDispatcher` 的会话串行队列保证单机顺序；`SessionLock`（Redis SET NX + Lua 释放）在**多实例**部署时兜底互斥。锁 TTL 可配，续期/看门狗在 M4 引入。
5. **分层循环防护**：编译期图环检测（Kahn）-> 运行期步数预算（`LoopGuard.maxSteps`）-> 节点重试上限；重试/步数用尽且节点声明了 `fallback` 时，运行转 `DEGRADED` 并向用户返回预设文案。工具调用指纹去重（tool+参数 hash）在 M3 引入。
6. **引擎与持久化解耦**：引擎只依赖 `RunRecorder`、`RunEventBus` 接口；JPA 实现放在 `persistence` 包。好处：引擎单测不需要 Spring/DB；后续换存储不动引擎。
7. **事件带单调 seq**：`RunEventBus` 为每条事件分配递增序号并保留环形缓冲（默认 2000 条），SSE 订阅带 `Last-Event-ID` 时可回放断线期间的事件；Redis Stream 持久化在 M4 替换。
8. **运行两级快照**：`workflow_runs`（整体）+ `node_runs`（每次尝试的输入/输出/耗时/错误），一次写入即是回放与审计的基础，也是评测（M2 黄金集）的数据来源。
9. **pgvector 先行**：单机起步用 PostgreSQL + pgvector（HNSW），减少运维面；真正的向量库（Milvus 等）在压测证明必要后再引入。
10. **检索分数先归一化再统一阈值**（M2 硬性要求）：云端知识库与本地检索的 score 量纲不同，`RetrievalProvider` 必须在适配层归一化，否则「低相似度丢弃 / 动态 top-k」在云端结果上不可比、不可用。
11. **`requires` 能力声明上移到节点定义**：节点声明能力集，路由层负责满足；这样「云端不可用 -> 本地兜底 -> 降级话术」是引擎语义，而非每个节点的散落 if-else。
12. **向量存储与检索策略分开**：`EmbeddingStore` 负责切片向量的持久化与候选集读取，`RetrievalProvider` 负责一次检索的语义（本地/云端同接口）。M2 本地实现是精确余弦（Java 计算 kb 级候选集）：`document_chunks.embedding vector(1024)` 与 HNSW 索引就位，但查询暂不走 ANN —— 规模未到，且要能离线单测；将来切 ANN 只替换检索实现，阈值与裁剪链不动。
13. **Embedding 与知识库都是可插拔 provider**：`EmbeddingProvider`（固定 1024 维）默认 mock（确定性哈希，离线可复现，供测试与压测）；生产走 OpenAI 兼容 HTTP（云厂商 / 本地 vLLM / Ollama 同一协议）。云端知识库同理走 `KnowledgeBaseProvider`。维度或响应结构不符一律快速失败，不静默截断。
14. **切片必须可溯源**：每个切片带 `docId / chunkIndex / sectionPath / pageFrom / pageTo / contentHash`；提示词用 `[n]` 编号，节点输出回传「编号 -> 切片 + 分数 + 来源」映射，SSE 与 `node_runs` 都能看到命中切片，引用可审计。
15. **检索后处理是纯函数链**：归一化 -> 绝对阈值过滤 -> 动态 top-k（断崖截断）-> MMR 去冗余 -> 相邻切片合并。每步无状态、可单测、顺序固定，便于回归对比指标。断崖落在 `minK` 保护区内时按 `minK` 截断（不是忽略断崖继续往后找），否则「第 1 名很高、第 2 名起全是噪声」会白白多带切片。
16. **质量门禁跑在 mock embedding 上，阈值按 mock 量纲校准**：黄金集回归必须离线、确定性、零成本，所以 `RagGoldenSetEvalTest` 用 mock embedding（只对词面重叠敏感）。它的余弦量纲比真实模型低，绝对阈值不能直接套生产默认值：门禁取校准后的 `MIN_SCORE`，报告里写完整阈值曲线（各 minScore 下的 recall / MRR / 拒答）便于复盘。**换真实 embedding provider 后必须重新校准并更新 `docs/quality-plan.md`**。

## 4. 一次消息的完整生命周期

1. `POST /api/v1/sessions/{id}/messages`（携带 `clientMsgId` 幂等键）
2. 落 `messages`（USER）与 `workflow_runs`（PENDING），立即返回 HTTP 200、`runId` 与接收状态 `QUEUED`；跑哪个工作流由会话决定：建会话时传 `workflowName` 绑定（如 `rag-chat`），不传则回退 `aiflow.engine.default-workflow`
3. `SessionDispatcher` 按 `sessionId` 串行调度；同一会话前一条任务未结束则排队（队列有容量上限，超限快速失败 429）
4. `RunCoordinator` 获取 `SessionLock`（跨实例互斥）-> 加载工作流 -> 调用 `ExecutionEngine`
5. 引擎按拓扑序执行节点：节点内重试、超时中断、步数预算、取消检查；每步写 `node_runs` 并发布事件
6. 客户端 `GET /api/v1/runs/{runId}/events`（SSE）：`id` 为 seq，支持 `Last-Event-ID` 续传，15s 心跳；发送 `RUN_SUCCEEDED` / `RUN_DEGRADED` / `RUN_FAILED` / `RUN_CANCELED` 后关闭连接并清理订阅
7. 结束：`workflow_runs` 落终态，assistant 消息落库（`reply` 优先，其次 `fallback`），发布 RUN_* 终态事件

挂起（人工审批）把第 4-7 步切成两段：`approval` 节点落一张待办后返回「挂起」，引擎把检查点写进 `run_checkpoints`
（变量快照 / 激活集合 / 下一个拓扑位 / 步数 / 定义版本），运行转 `WAITING_APPROVAL`，`RunCoordinator` 立刻释放会话锁与 worker
（不占线程、不占锁，重启不丢）；人（或超时扫描）决策后经 `SessionDispatcher` 重新入队、重取锁，从断点继续。
参数：`aiflow.approval.sweep-ms` / `sweep-enabled` / `sweep-batch-size` / `default-wait-seconds` / `max-wait-seconds` /
`default-max-level` / `admin-users`（节点参数可覆盖默认值）。契约见 `docs/approval-node-design.md`。
状态变化还会**旁路**投递一条通知（`log` / `webhook` 渠道）：不走事件总线（那是给页面 SSE 看的），
而是在审批事务**提交之后**入队到一个单线程有界队列，失败只计数、不重试、不回滚审批，排队满了丢最老。
webhook 出网复用工具的 SSRF 护栏。渠道与计数看 `GET /api/v1/approvals/notify`；事件语义见
`docs/approval-node-design.md` §12。

## 5. 事件模型

| 事件 | 时机 | data 关键字段 |
| --- | --- | --- |
| RUN_STARTED | 引擎开始执行 | workflowId / workflowName / version |
| NODE_STARTED | 节点某次尝试开始 | type / attempt |
| DELTA | 模型流式输出片段 | text |
| NODE_SUCCEEDED | 节点成功 | attempt / ms |
| NODE_DEGRADED | 节点降级（fallback 生效） | attempt / reason / fallback |
| NODE_FAILED | 节点某次尝试失败 | attempt / error |
| NODE_RETRY | 将进行下一次尝试 | attempt / nextAttempt |
| NODE_SUSPENDED | 节点请求挂起（人工审批，节点行转 WAITING 且 ended_at 留空） | type / approvalId |
| RUN_WAITING | 运行转入等待外部输入，释放会话锁与 worker | nodeId / kind / dueAt |
| APPROVAL_REQUESTED | 审批待办生成 | approvalId / nodeId / level / title / assignees / dueAt |
| APPROVAL_DECIDED | 人工决策落库（条件更新单赢家） | approvalId / decision / decidedBy / comment / waitedMs |
| APPROVAL_ESCALATED | 超时升级到下一层（旧层 EXPIRED + 新层 PENDING） | approvalId / fromLevel / toLevel / assignees |
| APPROVAL_EXPIRED | 审批超时收口（放行 / 打回 / 升级 / 随运行取消） | approvalId / level / action |
| RUN_SUCCEEDED / RUN_DEGRADED / RUN_FAILED / RUN_CANCELED | 运行终态 | status / steps / output / error |
| PING | 心跳（不经总线，直接由流服务发出） | - |

## 6. 运行状态机

```
workflow_runs: PENDING -> RUNNING -> WAITING_APPROVAL -> RUNNING -> SUCCEEDED | DEGRADED | FAILED
                                             └─(取消)──> CANCELED
node_runs:     RUNNING -> WAITING -> SUCCEEDED | DEGRADED | FAILED（每次尝试一行）
```

约定：只要有一个节点降级而运行最终完成，运行状态为 `DEGRADED`；`FAILED` 表示未产生可用结果；`CANCELED` 只由显式取消触发。

挂起（人工审批，V6）有两条不可妥协的语义，都有用例钉住：**不重放**已执行节点（恢复从挂起节点的下一个拓扑位继续，
否则工具会重复出网、模型会重复扣费）；**挂起期间不持会话锁**（同会话的下一条消息能照常跑完 —— 锁住会话等于把人不在的
时间也算进会话阻塞，是有意的取舍，见 `docs/approval-node-design.md` §2）。步数预算跨挂起累计，恢复前校验工作流版本。

## 7. 数据模型

见 `docs/data-model.md`。M1 六张表；M2 增加 `documents / document_chunks / embeddings`；V4 增加 `tool_audit_events`（审计落库）；
V5 增加 `documents.object_key`（原件留存）；V6 增加 `approvals / approval_events / run_checkpoints`（人工审批）与
`workflow_runs.waiting_node_id`；M4 增加 `memories / user_profiles`。

## 8. 安全与合规

- M1：无鉴权（本地开发显式传 userId）；无工具调用面。
- M3：工具默认全关，打开后走 host/端口白名单 + SSRF 四道校验（细节见 §13）；RAG 切片、文件内容、工具返回一律按**不可信数据**处理并加提示词边界声明；内容安全审核已落地（§13），输出侧另有一层流式增量审核，SSE 片段在**推送之前**就过规则。
- 工具授权与审计已落地（§13.1）：策略可限制"谁能调哪个工具"，每次调用留审计记录（结果口径含 DENIED），审计只存参数指纹不存原文，并落库到 `tool_audit_events`（异步写、跨重启可查）。
- MCP 已接入三种传输（stdio / Streamable HTTP / 老式 SSE 双端点），工具描述与返回值来自外部服务端，一律按**不可信数据**对待；远程形态的凭证只从环境变量注入（静态 Bearer 或 OAuth 动态令牌），401/403 的错误消息不回显任何头部值，令牌值不进日志与快照；SSE 的投递端点是服务端下发的，解析后必须再过一遍出网白名单；当前工具由工作流作者显式指定 id，没有「让模型自己挑工具」这一步，所以投毒能污染的是输出内容而不是调用链本身 —— 等做模型自主选择工具时，工具描述必须再过一遍审核。
- 人工审批（V6）：`decidedBy` **不是身份认证** —— 系统当前无鉴权，接口只校验「在不在审批人名单里」，任何人传对名字就能批。
  鉴权落地时在 `ApprovalController` 换真实身份来源（`docs/approval-node-design.md` §4）。
- M4/M5：声纹属于敏感生物识别信息——单独授权、加密存储、可删除、日志脱敏（声纹向量绝不进日志）。
- 日志：结构化应用日志与审计日志分开；敏感字段（手机号、证件号、声纹向量）不进日志。traceId/runId/sessionId/userId 通过 MDC 贯穿。

## 9. 检索链路（M2）

一次 `retrieval.search` 节点执行：

1. 查询渲染：M2 只做模板替换（`${input}` -> 用户问题）
2. `EmbeddingProvider.embed(query)` -> 1024 维；维度不符直接失败
3. `RetrievalProvider` 按配置的 sources 顺序取数：本地（精确余弦 + kb 过滤）/ 云端知识库（HTTP 或 mock）
4. `ScoreNormalizer` 按 source 归一化到 [0,1]：本地 `max(0, cos)`（不用 `(cos+1)/2`，否则无关切片恒为 0.5、阈值失效）；云端按配置量程线性映射
5. 融合去重：同 `contentHash` 取较高分；分数差在 `1e-9` 以内视为同分，保留先注册的来源。跨来源归一化会带进 1ULP 级浮点噪声（本地余弦 `0.5373…` 与云端量程往返后差 1ULP），不设容差就会出现「同一份切片在不同运行里归属不同来源」的漂移
6. 后处理链：阈值过滤 -> 断崖 top-k（minK / maxK / cliffRatio；断崖落在保护区内按 minK 截断）-> MMR（仅对有向量的切片生效）-> 相邻切片合并
7. `ContextPromptAssembler`：`[n]` 编号资料块 + 引用要求 + 注入边界声明
8. 输出变量：`context`（供 `llm.chat` 使用）、`chunks`（编号/切片/分数/来源）、`retrievalStats`
9. 引用标签（`citationLabel`）= `docTitle` + `sectionPath`；`sectionPath` 以 `docTitle` 开头时裁掉重复段 —— markdown 的 H1 会进标题栈，不裁会渲染成「报销制度.md > 报销制度 > 发票要求」

入库流水线（`DocumentIngestService`）：

1. 解析：text / markdown / json（PDF 与图片 OCR 归 M3）
2. 切片：按 markdown 标题层级与空行分段，目标 400 字、重叠 80 字，记录标题路径与页码；标题行不单独成块（拼到下一段正文前，首块即带标题路径），只由重叠尾巴构成的尾块丢弃
3. `EmbeddingProvider.embedAll` 批量向量化（1024 维）
4. 写 `documents` + `document_chunks`（向量列 + HNSW），`contentHash` 支持重复上传检测
5. 失败落 `status=FAILED` + `error`，不吞异常

参数（`aiflow.retrieval.*`）：`min-score`、`top-k`、`min-k`、`max-k`、`cliff-ratio`、`mmr-lambda`、`merge-adjacent`、`embedding.mode`（`mock` / `http`）、`embedding.min-score`（阈值跟向量模型走，`< 0` 即继承全局）、`embedding.model`、`embedding.base-url`、`embedding.dimensions`、`cloud.enabled/mode/base-url`。

> 向量来源绑定（V3）：`document_chunks.embedding_model` 记录向量由哪个 provider 生成，向量与模型标记必须同一条 SQL 写入（分开写会出现「标了 A、向量还是 B」的中间态）。检索只取与当前 provider 一致的切片；该 kb 有别的模型的向量时**显式报错并给出重建命令**，而不是返回空结果 —— 空结果会把排查方向引到「文档里没有这条内容」上去，那是错的。升级前的历史行标为 `(unknown)`，同样不参与检索。重建走 `POST /api/v1/kbs/{kbId}/reindex`：用切片自身的 content 重算向量，chunkId 与切片边界不变，已引用出去的 id 不会失效（切片策略变更仍需删文档重传）。
### 9.1 候选集来源：ANN 与精确余弦（2026-09-22）

本地检索的候选集有两条来源，**分数口径与后处理链完全共用**（`retrieval.post` 一行未改：阈值 / 断崖 top-k / MMR /
相邻合并只认分数，不关心分数是算出来的还是查出来的）：

1. **ANN**（`EmbeddingStore.supportsAnn()` + `searchSimilar`）：`order by embedding <=> cast(? as vector) limit N`
   走 `idx_chunks_embedding`（HNSW，`vector_cosine_ops`）。返回 `AnnHit(chunkId, cosine, embedding)`，
   再 `findAllByIdWithDocument` 回表组装正文与元数据 —— 向量沿用命中的那份，MMR 与精确路径用的是同一批数值。
2. **精确**（`load`）：把该 kb 该模型的全部向量读进 JVM 逐条算余弦（`Vectors.cosine`）。小 kb 的默认兜底，也是 ANN 的对照与退路。

选择规则（`LocalRetrievalProvider`）：`ann.enabled && supportsAnn()` -> ANN；**ANN 返回空则回退精确**
（带 where 过滤的 HNSW 在 pgvector 0.8 之前是后过滤，筛空是常态，空结果不能当成「没命中」）；ANN 抛错由存储层
自己去掉调优参数重试一次；存储不支持或 `ann.enabled=false` 直接走精确。候选宽度取 `max(ann.candidates, query.topK())`，
装配期对 `candidates < max-k` 额外 WARN（断崖 top-k 是在候选里挑的，候选比 max-k 还窄就永远挑不满）。

三条实现约束（均有单测钉住）：

- **`set local` 而不是 `set`**：HNSW 查询期参数必须与查询同事务生效，会话级 `set` 会把参数留在连接池的连接上、泄漏给后续别人的查询；
- **按扩展版本判定支持哪些参数，不用 `show hnsw.ef_search` 探活**：`hnsw.*` 是扩展 .so 注册的自定义 GUC，
  本会话没用过 HNSW 索引之前 `show` 会报 `unrecognized configuration parameter`，拿它探活会误判成「不支持」
  并把配置好的参数静默吞掉（实测踩过）；版本阈值：`ef_search` >= 0.5、`iterative_scan` >= 0.8；
- **调优参数失败不改检索结果**：`set local` 抛错只 WARN、关掉调优并重试一次（用越界 ef_search 实测触发过，结果不变）。
  正确性不许挂在可选性能开关上。

实测（本机 pgvector 0.8.6，2026-09-22）：真实语料（8 篇样本文档）ANN 与精确**逐条一致**；
5000 条切片规模基准 p50 **878ms -> 30.5ms（≈29×）**，两者 top-8 都等于标准答案（召回 8/8，首次调用含索引冷启动）。
脚本：`script/verify-ann-retrieval.ps1`（两条路径对照）、`script/bench-ann-scale.ps1`（规模基准）、
造数 `script/sql/bench-ann-needles.sql`。

## 10. 分级路由与降级矩阵（M3）

档位顺序由 `aiflow.provider.order` 声明，默认 `[cloud, local, mock]`：云端负责质量（加速与复杂任务），本地档留在链上兜底（断网时顶上），mock 只兜住流程不让链路 500。
默认值从「离线优先」翻成「质量优先」有实测依据：本机 1.5B 兜底模型在「从资料里抽答案」上明显不如云端（同一个 RAG 提问，检索都对，local 答「资料中没有」、cloud 能引用作答），把差档位放前面等于默认输出差答案；代价是离线时要等一次连接失败才降级，这个代价换来的是常态质量。要离线优先就把顺序换回去。去掉 mock 即「宁可不答也不假装答」。

一次 `llm.chat` 节点的档位选择：

1. 按 order 取候选：能力满足（`capabilities` 覆盖节点 `requires`）+ 未被 `mode=mock` 过滤
2. 探活/熔断判死的档位**跳过不试**，但记进 `skippedTiers` —— 省掉一次必然失败的等待，同时让「为什么答的是 mock」在快照里直接可见
3. 所有档位都不可用时，仍按 order 依次试一遍：健康状态是「大概率」而不是「绝对」，不能因为一条过期失败记录让兜底档位消失
4. 第 n 档抛错 -> 记 `degradedFrom` 换第 n+1 档。两种例外不换档：用户取消（`RunCanceledException`）；已经推送过 SSE 增量的调用（换了会拼出两段读不通的话，只能整轮失败）

健康状态（`EndpointHealth`，进程内、无第三方依赖）：

- 探活：每 15s `GET {base-url}/models`。2xx = up；401/403 = down（服务活着但密钥不对，必须报出来，否则云档案位会被反复选中、每次请求都失败）；其余 4xx = up（有些网关不实现 `/models`）；5xx / 连接失败 / 超时 = down
- 真实调用失败与探活失败写同一份状态：连续失败达阈值（默认 3）打开熔断，冷却（默认 30s）结束自动放行；任何成功即清零
- `available()` = enabled && 最近一次信号正常 && 未熔断。报告层据此如实回答「现在用哪一档」，路由层在全部不可用时仍会试
- 超时/取消的区分：线程中断代表用户取消 -> `RunCanceledException`，既不熔断也不换档
- 探活超时可按端点覆盖（`probe-timeout-ms`，0 = 继承全局）：探活打的是 `GET /models`，**清单大小由厂商决定**。
  火山方舟的清单有几百 KB，1.5s 的全局超时会让档位在 up/down 之间反复跳，实测有一次视觉请求正好落在 down 窗口
  里被判「不可用」直接跳过、降级到 mock，而那一档其实好好的。档位健康判定不该被响应体大小左右。

一个档位可以挂多个端点（`ark` / `qwen` / `cloud` 都是 `cloud` 档）：

- `order` 里写的是**档位名**，路由按「先 id、再档位名」匹配优先级。只按 id 匹配会漏掉新端点 —— 它在 order 里
  找不到自己，落到 `Integer.MAX_VALUE`，排在 mock 后面，成为一个永远轮不到的兜底（已修，`ProviderRouterTierOrderTest` 钉住）
- 同档位内优先级相同，由稳定排序保持**配置声明顺序**：谁写在前面谁是主、后面的当备。为此端点表的迭代顺序
  必须保序（`LinkedHashMap` + 不可变视图），`Map.copyOf` 的迭代顺序不定，会把主备关系变成随机
- `extra-body`（JSON 对象）是厂商私有请求体字段的口子：协议主干只覆盖到 `model` / `messages` / `stream`，
  各家的关键开关是私有的 —— 火山方舟的 `thinking`、DashScope 的 `enable_thinking` 都属于这一层。启动期解析并
  校验，试图覆盖协议主干直接让引擎起不来；没有这个口子，每接一家厂商就要改一次 provider 代码

能力矩阵（`GET /api/v1/capabilities`，控制台也展示同一份数据）：

| 档位 | 判定 | 含义 |
| --- | --- | --- |
| L0 全云端 | 生效档位 = cloud 且向量化真实可用 | 能力最全（含多模态），断网不可用 |
| L1 本地全功能 | 生效档位 = local 且向量化真实可用 | 离线全链路可用 |
| L2 本地模型 + 纯文本 | 生效档位是真实模型，但向量化档位不可用 | 知识库入库与检索降级 |
| L3 预设话术 / 模拟档 | 没有可用的真实档位（只剩 mock） | 由 mock 承接（节点输出 `provider=mock`，不假装是真实模型）；节点失败时才是预设话术 |

- 「降级」只统计**配置了却没起来**的档位（`expected`）：显式 `enabled=false` 是选择，不是故障，不该天天报降级
- 档位名默认按 base-url 推导（本机 = `local`），也可在配置里显式声明（`endpoints.cloud.tier=cloud`）：内网自建集群不是 localhost，但语义上更接近本地档，这件事只有配置的人知道，不该由猜地址的代码决定

向量化分级链：`embedding.mode=http` 时按 `embedding.tiers` 顺序兜底。各档必须是**同一个 model**，启动期强制校验，不一致直接启动失败 —— 不同模型的向量不在同一空间，允许互为兜底等于允许静默算错。`id()` 取模型身份（`http-embedding:bge-m3`）而不是「哪一档服务了这一票」，否则同一份索引在云端/本地之间切换时会被判成两个向量空间（V3 的来源绑定见 §9）。

M3 里已经落地的：多模态档位与图像输入（§12）、PDF 与图片入库与原件留存（§14）、工具调用与三种来源（§13.1）、工具粒度授权与调用审计（§13.1）、MCP 的三种传输形态与远程鉴权（Streamable HTTP + 老式 SSE 双端点，静态 Bearer 与 OAuth 动态刷新）、MCP 工具热更新的版本一致性（§13.1）、内容安全审核与流式增量审核（§13.2）、每用户 token 额度（§13.3）。仍未落地（见 roadmap M3 待补）：云端知识库真实厂商联调 —— 现有 `HttpKnowledgeBaseProvider` 是自定义契约（`POST /retrieve` + `{chunks:[...]}`），没有厂商原生说这套话，因此先交付 `script/probe-cloud-kb.ps1` 探形（真实请求/响应形状与分数量程一次抓全），再照真实协议写映射，不凭想象造适配器。复杂度分级仍走规则化路线，暂不引入分类器。

## 11. 复杂度分级（规划）

规则先行：输入长度、是否含工具意图、是否多跳；小分类器后置；每用户 token 预算超限自动降档。
## 12. 多模态与附件（M3）

契约：`ChatMessage.user(text, List<ChatImage>)`。`ChatImage` 只有两种合法形态 —— `http(s)://` 直链与
`data:image/*;base64,...` 内联。**刻意不支持本地文件路径**：那既是 SSRF 面（让上游去读任意地址），
也是信息泄露面（把服务器目录结构发给厂商）。本地图片必须先落 `AttachmentStore`，再编码成 data URI。
`ChatImage.toString()` 只输出类型与长度 —— record 的默认实现会把整段 base64 打进日志、异常栈与节点快照。

序列化：有图时 content 变成 OpenAI 多模态数组（text + image_url 两种 part），无图时保持纯字符串。
纯文本保持字符串是有意的：不少兼容网关对数组形态支持不全，无差别改成数组会把本来能用的请求弄坏。

存储：`AttachmentStore` 内容落盘（`aiflow.attachments.dir`，默认 `data/attachments`），元数据写同名 sidecar json。

- 类型以文件头嗅探为准（png / jpeg / webp / gif），改扩展名无效
- 落盘文件名 = UUID + 嗅探出的扩展名；原始文件名只作元数据保存并清洗截断，不参与路径拼接
- 读取由 id 反查元数据，且路径必须落在配置目录内（防目录穿越）
- 暂不建表：附件只服务「一次运行里的图片输入」，建表要连带处理删除 / 归档 / 级联，暂无消费者；对象存储落地时整体替换

节点：`llm.vision` 的 `requiredCapabilities = {chat, vision}`，两个都要满足 —— 把图交给纯文本模型等于让它对着 base64 猜。
执行顺序是有意为之：先判定有没有 vision 档位（`route()`），再读磁盘解析图片。没有档位时先给可操作结论
（「声明 capabilities: [chat, vision] 并配置 VL 模型」），而不是先报一句「附件不存在」。

降级留痕：vision 节点与 `llm.chat` 共用 `DegradationTrace`（provider / skippedTiers / degradedFrom / degradedReasons）。
mock 档同样声明 vision：离线时链路仍能跑通，但回复必须自报家门（「只证明图片进得来、节点跑得通」），
能力矩阵的 `notice` 同步说明，快照里 `provider=mock` 是第二条留痕 —— 不假装成真实识图能力。

## 13. 工具调用、内容安全与额度（M3）

### 13.1 工具调用：默认全关，三种来源共用一套护栏

`tool.call` 节点只做一件事：把（工具 id + 参数）变成一次真实的工具调用。它是引擎里唯一
「主动出网 / 拉起本地进程 / 读本机文件」的能力，所以默认 `aiflow.tools.enabled=false`，关掉时
`ToolRegistry.require(id)` 直接抛 `ToolBlockedException`（策略结论），而不是让请求走到一半再失败。

工具来自三个地方，登记在同一张白名单里 —— **统一的是「能不能被调用」这件事，不统一的是「要多严」**：

| 来源 | 工具 id 形态 | 执行方式 | 主要风险 | 约束 |
| --- | --- | --- | --- | --- |
| HTTP | 配置里的 key（如 `weather`） | 出网 HTTP 请求 | SSRF、数据外带 | 四道 SSRF 校验 + host 白名单 + 端口白名单 |
| MCP（stdio） | `mcp.<serverId>.<toolName>` | 本机子进程 + JSON-RPC | 本地进程与文件系统 | 命令只能来自配置文件或被显式声明的导入项 |
| MCP（HTTP） | 同上 | 远程单端点 POST + JSON-RPC（响应可为 SSE 流） | SSRF、凭证外泄、数据外带 | 与 HTTP 工具同一套出网白名单；凭证只从环境变量注入；会话 id 经字符集校验后回带 |
| MCP（老式 SSE） | 同上 | `GET` 常驻流 + 服务端下发投递端点 | 同上 + 端点指向内网 | 投递端点解析后重新过白名单（它是服务端给的输入）；流断即整条通道失败并只重连一次 |
| 技能库 | `skill.list` / `skill.read` | 读本机 `SKILL.md` | 只读，粒度最小 | 根目录白名单 + 正文截断 |

三个来源各有独立开关（`tools.mcp.enabled` / `tools.skills.enabled`）但都受总闸 `aiflow.tools.enabled` 约束：
风险不同所以要能分别关，但「引擎能不能调工具」这个心智模型只应该有一个入口。

装配期就失败的三种配置：url 为空、host 不在白名单、方法不在支持列表（GET/POST/PUT/PATCH/DELETE/HEAD）。
理由一致：这类错误属于「配置写错了」，应该在启动时炸掉，而不是在用户第一次点按钮时变成一个 500。

`SsrfGuard` 四道检查，顺序固定：

1. 协议只允许 http/https；**带 userinfo 的 URL 直接拒绝**（`http://user:pass@host/` 是典型的绕过手法）
2. 端口必须在 `allow-ports` 里（默认 80/443）
3. host 必须在 `allow-hosts` 里，支持 `*.example.com` 通配，且按**域名边界**匹配 —— `evilexample.com` 不通过
4. 解析后的地址不得是回环 / 内网 / 链路本地（含云元数据地址 `169.254.169.254`）/ ULA / 组播 / CGNAT，
   除非该 host 显式列在 `allow-private-hosts` 里

第 4 条是「DNS 解析结果」而不是「主机名字符串」：只看字符串的 SSRF 防护，挡不住 `internal.example.com` 解析到
`10.0.0.5`。这也是为什么转发用的 `FollowRedirects.NEVER`：一次重定向就能把白名单校验绕过去，
`3xx` 一律按失败处理并在错误里写明原因。

运行级护栏（`ToolInvocationGuard`，按 runId 隔离）：

- 单次运行的调用次数上限（`max-calls-per-run`）
- 同（工具 + 入参）指纹重复超过 `loop-threshold` 判为**工具循环**，直接失败 —— 模型反复调同一个工具
  是 agent 场景最典型的失控形态
- 幂等缓存：`idempotency: content` 的工具，同参数直接回放上一次的响应（快照里标 `replayed=true`）
- 响应体按 `max-body-bytes + 1` 读取并标记截断，避免把一个大文件读进节点快照

#### 授权与审计

**授权**回答"这个人能不能调这个工具"，与"工具开没开"（`enabled`）是两件事，分两层的原因很实际：
同一个 MCP 服务端里读文件与写文件是两个工具，给不给某个人用完全是另一回事。判定放在执行器里而不是注册表里，
因为注册表是**装配期**概念（有哪些工具）、授权是**运行期**概念（这次调用是谁发起的）—— 把用户维度塞进注册表，
"工具清单"就变成了"某个人能看到的清单"，缓存与测试都会跟着复杂。

三条语义必须有测试钉住：**首个命中生效**、**deny 压 allow**、**空白名单不等于"所有人"**（没有用户上下文时，
只有显式 `*` 的规则才放行）。拒绝要发生在**调用之前**并把原因带进节点错误 —— 用户看到"为什么"而不只是"不行"。

**审计**单独记一份，不靠 `node_runs` 快照：快照是工作流视角（这个节点干了什么），审计是主体视角
（谁在什么时候用了哪个工具、成没成）。参数只记指纹不记原文 —— 工具参数里常有手机号、token，
审计要能定位"是不是同一个调用"，但不该变成第二个敏感数据仓库。

审计有三条腿，缺一条都不完整：**内存环形缓冲**（"此刻能查"，代价近零，重启即空）、
**结构化日志**（logger 名 `aiflow.audit`，事实流，可采集可对时间轴）、
**落库**（表 `tool_audit_events`，跨重启追溯）。三条腿由 `ToolAuditService` 一次 `record` 同时喂，
落库那条是**异步**的：调用路径上只多一次入队，写库在自己的线程里 64 条一批地做。

异步必须配一套诚实的降级语义，否则"审计不丢"就是空话：队列满**丢最老的**（近期记录更值得留）、
写失败**只计数不重试**（无条件重试只会把队列堵死，最后丢得更多）、丢了多少/失败多少都暴露在
`GET /api/v1/tools/audit` 的 `droppedWrites` / `failedWriteBatches` 上。查询优先读库，库不可用时
自动回退内存缓冲，并把数据来源写在 `store` 字段里 —— 看结果的人必须知道这份结果能回溯多远。

顺带钉住的一条引擎语义：**策略拒绝不重试**。拒绝是确定性的（同一个用户、同一条策略，重试多少次都一样），
重试只会多等一轮退避，还让审计多出一行"被拒"（审计记的是尝试次数，重复记录会把排障带偏）。

#### MCP 服务端

客户端是自己实现的（`initialize` -> `tools/list` -> `tools/call`），三种传输共用一个 `McpClient` 接口 ——
装配、发现、调用、护栏都是同一条路径，差异只剩「消息怎么走」：

- **stdio**：newline-delimited JSON-RPC 2.0。每个服务端一条常驻进程，**串行调用**（一个子进程上的并发复用需要按请求
  id 路由与背压，先不做）；stderr 单独抽干并保留末尾若干行 —— 子进程最常见的失败形态是「启动了但立刻退出」，那时
  唯一有用的信息在 stderr 里，所以拉起失败时把 stderr 尾部贴进失败原因。调用超时即杀进程，不留半死不活的子进程。
- **Streamable HTTP**：单端点 `POST`，`Accept` 同时带 `application/json` 与 `text/event-stream`；响应是 SSE 时按行
  解析事件（`data:` 可跨多行、`:` 开头是心跳注释、还混着服务端主动推送），只认 id 对得上的那条。会话 id 由服务端在
  响应头下发、后续请求回带；服务端回 404 说明会话失效，**重新握手并重试一次**（只一次 —— 一直失效是配置或鉴权问题，
  重试掩盖不了）。读取带超时，超时关流：HTTP 形态没有进程可回收。
- **老式 SSE 双端点**（Claude 配置里的 `type: "sse"`）：状态机与前两种不同 —— 先 `GET` 建一条常驻 `text/event-stream`，
  等 `event: endpoint` 下发「消息往哪 POST」，之后 `POST` 只负责投递（规范回 202 空体），**响应从那条常驻连接回来**，
  靠 JSON-RPC id 配对（流上还混着心跳注释与服务端主动推送的通知）。三条硬规则：① 端点是服务端给的**不可信输入**，
  相对路径按建流地址 resolve 后重新过 `SsrfGuard`；② 流断 = 整条通道断，`failPending` 让在途请求立刻失败，
  下一条请求重新建流、重新发现端点、重新握手，且**只重连一次**；③ `close()` 关流让读线程自然结束，读线程不被中断
  （挂着一条打不断的线程比多等 100ms 更糟）。

HTTP 形态**每次请求都过一次 `SsrfGuard`**：注册时那一遍只证明「配置时」是安全的，域名解析结果会变，出网白名单与内网
地址检查要落在真正发请求的那一刻（与 `HttpToolInvoker` 一致），不跟随重定向的理由也相同。

凭证有三个入口，静态与动态**二选一（配置期就报错，不留运行期猜谜）**：

- `auth-token-env`（环境变量名）与 headers 值里的 `${VAR}` 占位符 —— 静态场景；
  **变量没设就装配失败**，不静默留空（静默会把「凭证没配」变成一串 401，或者更糟 —— 一次匿名调用）。
- `oauth` 配置块（token-url / client-id-env / client-secret-env / refresh-token-env / scope / extra-params）：
  有 refresh-token-env 走 `refresh_token` 授权，否则 `client_credentials`；到期前 `early-refresh-seconds`（默认 60）提前刷，
  并发调用撞上过期时**单飞**（锁内二次检查，只发一次刷新）。令牌端点同样过 `SsrfGuard` —— 刷新流程不能变成绕过出网白名单的后门。
  对外只暴露「还剩多少秒过期」（`describe()`），令牌值不进日志、不进快照。

401/403 的错误只提示检查哪个变量，不回显头部值。

**单服务端起不来只记 `failures`，不炸 Spring 上下文**：工具是可选能力，一个配错的 MCP 服务端不该让整个引擎起不来。
配置错误（url 非法、同时给了 command 与 url、host 不在白名单、凭证变量缺失）与连接失败一样记进 `failures` ——
「跳过了」「配错了」「连不上」必须能区分开。`transport` 显式声明形态（`auto` 按 command / url 推断），不能靠「有 url 就当 HTTP」
去猜 —— 猜错的代价是把老式 SSE 服务端当单端点打，然后收到一堆看不懂的超时。

工具清单是**外部事实**：`POST /api/v1/tools/reload` 先让各来源重新发现（`McpServerManager#reload` / `SkillCatalog#rescan`），
再把发现结果**重装进 `ToolRegistry` 的白名单快照**（只重装外部来源，HTTP 仍走配置 + 重启）。`reload()` 的次序是**先装新、再等旧退场** ——
新连接装好才交接，等在途调用跑完（`drain-timeout-ms`，默认 5000）才关旧连接，所以 reload 不会掐掉正在执行的调用；
等超时改为在线程上异步强关并留 WARN（同步关会撞客户端的 `synchronized` 锁，把 reload 一起拖住）。

一致性靠三样东西兜住：每次装配记 `version`（transport | id | 工具指纹的 sha256 前 12 位）与 `generation`；
`McpToolDescriptor` 带 `fingerprint`；调用前 `verifyTool` 校验「工具还在不在、定义变没变」—— 工具消失或定义变化时
**明确失败并提示重新发现**，而不是把旧参数发往已经变了的接口。`GET /api/v1/tools` 的 `mcp.servers` 里能看到
`version` / `generation` / `loadedAt` / `auth` / `inFlight`，排障时不必猜「这个服务端现在是不是我刚改的那份」。

诊断出口里的外部地址只保留协议、host、显式端口和 path；userinfo、query、fragment 一律剥离。HTTP 工具的实际请求仍使用
渲染后的原始 URI，MCP 客户端也仍按原始配置连接，脱敏只发生在工具清单、运行快照、失败原因和节点输出，避免把一次性令牌或用户参数写进持久化诊断。

配置导入（Codex 的 `[mcp_servers.*]`、Claude 的 `mcpServers`）是为了降低接入成本：本机已经装好的 Agent 工具链，
填一个路径就能进白名单，不必手抄一遍命令。导入项与手工声明合并，id 加前缀区分来源（`codex-` / `claude-`）。

#### 本机技能库

技能（`SKILL.md` + frontmatter）本质是「带元数据的本地知识文件」，正好是「工具清单 + 可读取的说明」这一对。
暴露的是 **`skill.list` / `skill.read` 两个工具**，而不是「一技能一工具」：技能有 25 个且会增长，
逐个注册会让工具清单变成一份要反复读的目录，也与「先搜索、再按需读正文」的实际用法不符。
新增技能只需往根目录里放文件，配置零改动。

与 MCP、HTTP 一样走同一套运行级护栏（次数上限、循环指纹、幂等缓存）。MCP 的 `isError=true` 按节点失败处理，
与 HTTP 4xx/5xx 一致：把「工具报错」当成功会污染下游判断。工具输出统一按**不可信数据**对待，不进系统提示词的可信区。

### 13.2 内容安全：规则层，两个方向同一套规则

三条取舍写进代码注释，这里给结论：

- **超长直接拒绝，不静默截断**：用户以为发了全文、系统只处理了前 4000 字，比报错更坏
- **只回类别不回命中词**：回显命中词等于把绕过词表的线索送给调用方；命中的具体项写 DEBUG 日志
- **词表可外置到文件**：真实词表动辄上千条，塞进 yml 会让配置文件的 diff 变成噪声

拦截位置决定了它的价值：输入侧在**写消息、排队、调模型之前**（违规内容不落库、不占队列、不花钱），
输出侧在**返回给用户之前**（回复换成预设话术、run 标记 `DEGRADED`、输出带 `blocked` / `blockCategory`）。

**流式增量层（`StreamingSafetyGuard`）**：只作用于完整回复有个硬伤 —— SSE 片段在生成过程中早就推送出去了，
「最终答案是预设话术」与「用户已经读到违规片段」可以同时成立。做法是**保留窗口**：最近「生效窗口」个字符
先扣在缓冲里，下一批片段到达时连同窗口一起过 `scanRules`，通过后才放行窗口之前的部分。

生效窗口不是手填的那一个数：`hold-back-chars`（默认 32）只是**下限**，`auto-window=true`（默认）时窗口取
「`hold-back-chars` 与词表里最长敏感词长度」的较大值，上限 `max-auto-window`（默认 200）。换言之
「词表里放了一个比窗口还长的词」不会留下「先推送、后拦截」的缝 —— 这条缝由装配期按词表实际情况自动补上，
代价只是多延迟几个字符；只有病态超长（有人往词表里粘了一整段话）才退回最终整段审核兜底，并记 WARN。

窗口重叠是「不漏」的依据：每次检查的是「上一段末尾 (窗口-1) 个字符 + 本段候选」，所以任何长度不超过窗口的
命中串，只要有一个字符落在本片段，就必然完整出现在本次检查范围内，而命中后整个候选段都不放行。
因此**承诺有明确边界**：长度 ≤ 生效窗口的命中，整词绝不会出现在已推送内容里；但放行按冲刷边界走、不按词边界，
所以命中串的**前缀**可能已经流出（窗口 6 抓「杭州」那轮推送出去一个「杭」字）—— 这是窗口语义的必然代价，
换来的是审核真正跟着流走。代价是最多延迟窗口大小的输出。

长度规则（`max-input-chars` / `max-output-chars`）**不进增量层**，只在完整输入与完整回复上判：
流式片段长度没有语义。规则层也不是模型层审核的替代品，两者互补：这一层可解释、零延迟、可回归，
先挡掉明确不该进出的内容。

实测（2026-09-20，真实 `deepseek-flash`，词表含「杭州」，窗口 6）：run `2ed79773` 终态 `DEGRADED` + 预设话术，
事件流里 DELTA 只有 1 条、文本为「杭」，推送出去的内容不含「杭州」；窗口保持默认 32 而回复较短的那次 run 是 0 条 DELTA，
整个回复被扣在窗口里直接判命中。输入侧不受影响，仍在排模型之前把违规消息挡成 400。
生效窗口默认 ≥ 最长敏感词（自动抬高），所以默认配置下不存在「先推送命中串、后拦截」的窗口；只有词长超过
`max-auto-window` 时才落回最终审核 —— 装配期会 WARN 说明是哪些长度，不静默降级。

### 13.3 每用户 token 额度：账本 + 换档 + 熔断式拦截

额度的三个决定：

1. **按用户、按自然日**。按会话算等于换个会话就能续命；按全局算等于一个用户能把所有人吃光。
   跨天靠 Redis / 内存 key 上的日期 + TTL 归零，不靠定时任务 —— 依赖人工清理的计数器，最后都会被忘掉。
2. **两级阈值的作用不同**。软阈值（默认 80%）只**换档**（`provider.cheap-tiers` 提前，降级但可用）；
   硬上限才**拦截**，返回预设话术并把 run 标记 `DEGRADED`。额度用尽是产品行为，不是故障，不该显示成 FAILED。
3. **判定在调用前，扣减在返回后，审核在扣减之后**。于是最后一个请求可能略微超出上限 ——
   这是刻意换来的：为了精确而不把已经生成的回答丢掉，比超支几十个 token 更糟。审核放在扣减之后，
   是因为「内容违规」和「这些 token 已经花掉了」是两件事。

计数存储两种口径，`TokenUsageStore` 一个接口：`memory`（默认，单实例 —— 多实例下额度会被放大到「实例数 × 额度」，
这一点必须写在配置注释里而不是留给用户猜）与 `redis`（`INCRBY` + `EXPIRE`，多实例共享；Redis 不可用时退化为
进程内计数并打一条带节流的 WARN）。降级方向是刻意选的：额度是成本控制，不是安全边界，
不该因为一个附属组件而让每一次对话都失败。

`GET /api/v1/budget/users/{userId}` 是这套机制的可观测面：用了多少、离降档还有多远、什么时候重置、计数走的哪种口径。
额度一旦生效，「为什么这次回答变慢了 / 换了档位」就必须能被解释。
## 14. PDF 与图片入库（M3）

两段式：**先抽文本层，抽不出字才 OCR**。这不是优化，而是成本模型决定的顺序 —— 一份 50 页的电子 PDF
通常 0 页需要 OCR，反过来做（先渲染再判断）等于每页都买一次模型调用。

```
上传字节 -> DocumentParser.detectType（按扩展名分诊）
        -> DocumentExtractor
             ├── PDF  -> PDFBox 逐页抽文本层
             │           └── 该页字数 < min-page-text-chars -> 渲染成 PNG -> OcrProvider
             ├── IMAGE -> 直接 OcrProvider
             └── 其他  -> 按 UTF-8 纯文本（text / markdown / json）
        -> TextChunker.chunkPages（按页切片，切片带 pageFrom/pageTo）
        -> 向量化 -> documents / document_chunks
```

三条边界：

- **判据是「这一页的字数」而不是「有没有字」**：PDF 里一页只抽得出页眉页脚的情况很常见，那种残页进知识库
  只会污染检索，按扫描页处理更诚实。
- **OCR 页数超上限直接失败**，不静默截断：300 页的扫描件只入库前 10 页，用户以为整本可检索 —— 这比报错更坏。
  要处理大文件就调大上限或拆分，决定权留给人。
- **按页切片，不跨页合并**：页码是 PDF 引用的唯一定位方式，跨页合并后说不清「这条来自哪页」。
  代价是跨页句子被拆成两条切片，对召回无实质损失，而「引用页码错一位」会让人白翻一页。

OCR 的实现放在 `integration`（`VisionOcrProvider`），接口在 `ingest`：解析流程不该知道文字是哪个厂商认出来的，
这样扫描件入库既可以用云端多模态档，也可以换成专用 OCR 引擎，流水线不动。提示词按「转写」写而不是「描述」写 ——
模型默认倾向总结与解释，那对知识库是有毒的：入库的必须是原文。

**原件留存（对象存储）**：切片进 PG、原件进对象存储，两边各拿各擅长的。二进制不进数据库的理由很实际 ——
原件动辄几 MB，塞进 PG 会把备份、复制、连接池一起拖慢，而它的访问模式（低频、整份读、按 key 取）
正好是对象存储的形状。键形如 `kb/{kbId}/documents/{documentId}/{fileName}`，库里只记 `object_key` / `object_bytes`。

这一层是**可选能力**（`aiflow.storage.enabled`，默认关），因此它的失败语义必须是旁路的：
对象存储写不进去只记 WARN、`object_key` 留空，**入库照旧成功**（`INDEXED`）。把可选依赖做成硬依赖，
代价是"对象存储抖一下，用户连知识库都用不了"。响应里直接给出 `objectKey`，不用翻日志就知道原件留没留住。

下载接口（`GET /api/v1/kbs/{kbId}/documents/{id}/original`）把三种"没有原件"分开报，因为排障结论完全不同：
能力没开 -> 503、这次没留住 -> 404 且写明是留存失败、对象存储里对象不见了 -> 404 且写明是对象存储侧的问题。
接口抽象成 `ObjectStorage`（put / stat / open）而不是到处直接调 SDK：测试用内存实现就能验完
"存了没 / 取回一致不一致 / 存不上会不会拖垮入库"，真实 MinIO 只留给联调（2026-09-22 已跑通：真实 MinIO 上传/下载 SHA256 一致，`mc ls` 在 MinIO 侧独立确认；`docker stop aiflow-minio` 后上传仍 `INDEXED`、`objectKey=null`、取原件 404，`docker start` 后再传一份恢复为对象已留存 —— 瞬态失败可自愈，不是永久退化）。

## 15. 短期记忆：窗口注入与滚动摘要（M4-1）

`llm.chat` / `llm.vision` 发出的不再是一条 `user(prompt)`，而是**装配结果**
`[system(摘要)] + 窗口历史 + 本轮输入`（`memory/SessionContextProvider`；窗口策略是纯函数 `trim`：
从最新往回留，超字符预算丢最老的，单条超预算**截断**而不是整条丢）。摘要带固定标签 —— 没有标签，模型会把压缩后的旧内容当成用户当前说的。

两侧边界都画在「不影响主链路」这一侧：

- **读侧（装配）**：任何读失败（表没迁移、连接抖动、内容异常）都降级成「没有记忆」，只累加 `readFailures`（暴露在 `GET /memory`）。
  多一段上下文是增强，少一段不该让运行 500。
- **写侧（摘要）**：运行收口后在**运行线程之外**触发（`MemoryMaintenanceExecutor`），队列锁此时已释放；同一会话同时只跑一次折叠
  （进程内 `inFlight`）；推进走**条件更新** `where summarized_count = :expected`，多实例同时折叠只有一个赢。失败只记账
  （`attempts` / `last_error`），连续失败到上限就停用该会话 —— 坏会话不该每轮烧一次模型调用。
- **折叠口径**：`foldable = max(0, 总消息数 - 窗口条数)`，`foldable - summarizedCount >= chunk` 时才折
  `[summarizedCount, min(foldable, summarizedCount + chunk))`；**原消息不删**，只推进前缀计数。
- **可观测**：`GET /api/v1/sessions/{id}/memory`（状态）、`/memory/preview`（把装配结果摊开：逐条 role / chars / 正文截断 500 字）、
  `POST /memory/rebuild`（清摘要与计数、**保留 token 累计**）；折叠完成另发 `MEMORY_SUMMARIZED` 事件（尽力而为，SSE 客户端通常已断开）。
- **配置**：`aiflow.memory.*`（环境变量 `AIFLOW_MEMORY_*`）。窗口注入默认开（只读库），滚动摘要默认关（要调模型，与 OCR 同口径）；
  节点参数 `history`（`inherit` / `none` / 数字）与 `includeSummary` 可按节点覆盖部署默认值。

## 16. 长期记忆：跨会话的事实与偏好（M4-2）

M4-1 解决的是「这一轮该带多少上下文」，它天然只有**会话**视野：换一段对话，用户昨天说过的
「我们公司报销要附行程单」「回答别超过三行」就全丢了。把整段历史一直塞进 prompt 不是解 ——
那是把**检索**问题当成**装配**问题解，成本线性涨、噪声同步涨。长期记忆要的是**用户级的稳定知识**：
作用域是 `userId`，与 `sessionId` 无关（这是整切片的立项理由，真实栈脚本第 3 节专门钉它）。

装配顺序固定为 `[system(长期记忆)] + [system(摘要)] + 窗口历史 + 本轮输入`，两块 system 各带自己的标签。
标签不是装饰：长期记忆是「用户一向如此」，摘要是「他刚才这么说过」，模型必须能分辨；混成一段，
「这轮别用中文回答」这种临时要求会被长期偏好盖住。

**抽取（写侧）**挂在运行收口之后的旁路上，与摘要共用 `MemoryMaintenanceExecutor` 的语义：不占会话队列、
失败只记账、运行早已成功。它在**取窗口之前**先过三道节流（会话累计消息数 / 距上次抽取的新增条数 / 最小间隔），
不通过就连一次库都不查；调用走「优先便宜档」（`preferCheapTiers=true`），因为它是维护性调用、不是用户等的那一次。
抽取的对话 token 与逐条 embedding 都**计入用户额度**，额度耗尽就跳过（下次运行再试）。

两条不许在重构里改掉的语义：

- **归并只看模型判的 `supersedes` + 人工优先**：不看「谁新」也不看「谁置信度高」。同一 `(user_id, kind, mem_key)`
  槽位上，`supersedes=false` 的冲突条目直接丢弃并记 `REJECTED` 事件；`MANUAL` 条目**永不被抽取覆盖** ——
  用户亲手写的纠正比模型的推测可信，这条一放松，「我改过的记忆又被模型改回去」就没法解释。
- **禁写清单在代码里再过一遍**，不只写在提示词里：提示词是「模型会照做」，这里是「就算它不照做也写不进去」。
  记忆落库后会被后续**每次对话**召回，一条不该记的东西不是「多了一行数据」，而是「在用户每次提问时重复复述」。
  按类别关键词 + 字面形态（手机号 / 长数字串 / 邮箱）双判，宁可不记。

**召回（读侧）**复用检索那一套：阈值默认继承 `retrieval.embedding.min-score`（换向量模型就得换阈值），
`countByUserId == 0` 时**短路不调 embedding**（没存过记忆的用户零成本）；向量存 `user_memories` 的 pgvector 列，
ANN 查空时回退精确余弦；向量模型与当前链不一致的条目不参与余弦，并在诊断里写明 `stale-embeddings:<model>`。
预算做三重裁剪（阈值 / 条数 / 字符），**放不下就整条丢、不截断** —— 被切一半的事实比没有事实更容易误导。
召回 query =「本轮输入 + 会话摘要前 N 字」：用户这轮只说「那它呢」时，摘要是唯一能召回出东西的线索。

**两个预览出口**（真实栈上唯一能断言「模型到底收到了什么」的东西，M4-1 的教训）：
`GET /api/v1/users/{id}/memories/preview` 摊开召回明细（命中、分数、丢弃原因、最终注入块），
`GET /api/v1/sessions/{id}/memory/preview` 摊开装配结果（哪几条 system 块 + 窗口 + 本轮）。
两者都是 `writeBackHits=false` 的口径：**看一眼不算一次使用**，预览不回写 hits / last_used_at。
这条口径是真实栈抓出来的 —— 第一版会话预览走的还是旧装配入口（没有 userId、没有长期记忆），
于是长期记忆开着时**预览会撒谎**：真实运行注入了，预览却显示没有。

**失败语义**与 M4-1 完全一致：读库失败、embedding 失败、query 异常，一律降级成「没有长期记忆」，
只累加 `readFailures`；构造上与没开这个功能时一模一样。记忆是增强，不是主链路。

**不做**（写在这里，避免以后当遗漏）：跨用户 / 团队共享记忆、自动衰减与遗忘曲线、多查询扩展与重排、
把长期记忆做成工作流节点、多模态记忆（图片只记文字描述）、跨设备同步与导入导出。

## 17. M4-3A 实施契约（本轮实现与核心验收完成）

- `aiflow.dispatch.enabled=true` 默认用 PG 持久调度，测试基线显式关闭；worker 可单独关闭供手动领取测试。
- 新消息与 PROCESS 同事务，最终审批决策与 RESUME 同事务；入队短锁 sessions 行，容量溢出 429 整体回滚。
- worker 只领取同会话最小未完成 seq；PG task 租约和 Redis 锁守护执行。挂起结束当前 task，批准创建新 task。
- 围栏在同事务锁 task 并校验 owner/status/数据库时刻的有效 lease，再写 run/node/message/checkpoint；RUNNING 过期不重放，QUEUED 可再领取。
- RunExecutionRegistry 为运行和节点线程捕获当次 guard，close 后旧 future 不得误用新 owner；check 包含取消，write 允许有效 owner 收口取消。
- 每个 dispatch lease 用独立生命周期锁协调续期与 complete/requeue/失败收口；执行节点期间不持此锁，收口成功后迟到续期不再访问 PG 或累加失租，活动续期失败仍立即使 guard 失效且只计一次。
- 事件 publishSafely 仅隔离 EventPublishException，返回 Optional 不伪造 seq；事件故障不改业务终态，失租仍停止执行。
- aiflow.events.mode 默认 memory；redis 模式 max-entries=2000、retention=24h，序号 counter 无自动TTL。
- XREAD 广播、连接独立有界发送队列；非法游标400、缺口/过期409；不静默切memory或假报完整空流。
- SSE 响应已提交且 Content-Type 为 `text/event-stream` 时，传输 `IOException` 只记固定 debug 信息并停止回写 JSON；未提交或非 SSE 的 IOException 仍按服务端故障返回 500，其他异常与既有 4xx 契约不变。
- Redis 事件 `readFailures` 只计活动订阅的真实读取故障；终态退订或总线关闭导致的 XREAD 中断不计失败、不新增错误回调，空闲快照故障只计一次。
- QUEUED取消与run同事务，RUNNING记录task.cancel_requested_at传至执行实例；WAITING保留现有CAS。
- 控制路径统一锁序为 session（需要入队时）→task→run→approval；审批决定/超时恢复先锁 session、该 run 未完成 task 与 run，确认挂起检查点，再 CAS 审批与入队。
  人工取消挂起运行不入队，先按 seq 锁该 run 的未完成 task，再 CAS run、收口审批与检查点。
  此事务使用显式控制作用域，不借活动 worker 的 guard；worker 始终绑定最初领取的 guard，避免旧线程借新 owner 写入。
  取消事件只在提交后发布，回滚的取消不得进入事件流。
- RESUME 领取后若尚未消费原检查点就崩溃，不能把仍处于原 WAITING 的 run 当作已完成执行段；按结果不确定收 FAILED。
  恢复执行后挂在新检查点则保留 WAITING 并仅收 task，既不重放原段也不取消新的审批。
- GET /api/v1/runs/events/status 返回模式、发布/读取失败、reader/subscriber/connection数与overflow，不返回正文或密钥。
- Redis Compose启用AOF everysec与数据卷；分别验证应用/Redis重启和容器重建，AOF主机异常存在最后一秒丢失边界。
- Redis 命令等待默认 2s（AIFLOW_REDIS_TIMEOUT），连接等待也设 2s（AIFLOW_REDIS_CONNECT_TIMEOUT），避免失联后 guard 长时间阻塞。
  小 TTL 验收实例需显式设小于 TTL/3 的命令等待上限；XREAD 的 read-block 应比命令等待更短。

本轮 M4-3A 验收结果与限制见 [m4-3a-validation.md](m4-3a-validation.md)。

## 18. M4-3B 实施契约（已实现并完成本轮验收）

完整契约见 [profile-design.md](profile-design.md)。画像控制状态在 V10 `user_profiles`，默认关闭；GET 不建行，PUT/DELETE 校验 `expectedVersion`。重置是保留源记录的逻辑停用，重新启用重新确定统计起点。

GET/PUT/DELETE `/api/v1/users/{userId}/profile` 返回状态与最近30天实时统计，`.../profile/preview` 返回显式偏好决策。运行按 created_at，工具按持久审计 at，对话 token 按 chat/vision 节点尝试 started_at/output 汇总；未知用量不填0，覆盖边界明确返回。

仅 MANUAL/PREFERENCE 的 response-style、model-policy 枚举生效；核心端口与纯规则放 `engine/profile`，画像不复制偏好正文。chat/vision 与会话预览共用详细装配结果，画像块有独立标签，新增 profileIncluded；节点 profilePreference=none 禁用画像应用，显式 model 不受画像 cheap-first 改档。

画像关闭不改变既有长期记忆。当前 API 仍为本地开发用途，没有主体与 userId 的完整归属隔离。

本轮全量 606 条、0 失败、0 错误、1 跳过；真实画像 23/23，短期记忆 20/20、审批 4/4、长期记忆手工/召回 21/21，控制台浏览器手验通过。完整证据与未覆盖项见 [m4-3b-validation.md](m4-3b-validation.md)。

## 19. M4-3D 语音契约（D0、D1、D2 mock 已验收）

- ASR/TTS 是独立于 `ChatProvider` 的能力端口：接口、DTO、`WavPcmValidator` 与 `AudioLimits` 放 `engine/audio`，厂商适配放 `engine/integration/audio`。不能复用 `ProviderProperties` 的 chat endpoint、`ProviderRouter` 的 SSE 语义或图片 `AttachmentStore`。
- D0 交付厂商无关的 mock 契约、同步请求/响应 DTO、脱敏异常和能力报告；厂商 HTTP contract stub 待真实协议确认后实施。配置使用独立 `aiflow.audio.*`，默认关闭；D0 只保存合法凭证环境变量名，不读取其值。
- D0 默认 WAV/单声道/16kHz/16bit 未压缩 PCM/≤30 秒/≤5MiB，TTS 文本最多 2000 个 Unicode 码点。校验 RIFF 总长、块边界与 padding、唯一 fmt/data、byteRate/blockAlign、非空完整采样帧；时长向上取整到毫秒。音频不持久化、不进入会话消息、记忆或数据库表；mock TTS 生成静音 WAV 并按同一限制验证。
- PCM 限制支持配置 1..192000Hz、1/2 声道、8/16/24/32bit，输入必须匹配所配参数。mock TTS 仅输出 16kHz/单声道/16bit 的 100..2000ms 静音，`voice` 不影响输出。`available()` 校验至少一个最小样本可行：ASR 为一个完整 PCM 帧，TTS 为 100ms/3244bytes；限制不足时报 `unavailable`，不保证所有请求通过。
- 未启用、通道关闭、非 mock/local 模式或没有可用 provider bean 时，`GET /api/v1/capabilities` 的 `asr`/`tts` 通道为 `unavailable` 并说明原因；mock 状态明确标注 `mock`，local 状态明确标注 `local`，不改变既有 L0-L3 对话/向量档位计算。能力可用性必须与实际装配的 provider 一致。
- D1 已验收：同步 `POST /api/v1/audio/transcriptions`（multipart `file` → JSON ApiResponse）与 `POST /api/v1/audio/speech`（JSON `text`/可选 `voice`/`format` → WAV 二进制）已接入 mock 端口，详见 `m4-3d-plan.md` 与 `m4-3d-d1-validation.md`。model 取通道配置，两个成功响应均 no-store，TTS 元信息头校验格式/长度；错误统一固定信息的 JSON（400/413/415/502/503/504）。AudioValidationException 使用稳定 Reason，禁止匹配消息字符串分流。音频独立 5MiB 限长读取，不收紧图片/文档的全局 multipart 限额；Servlet 临时文件由容器清理。D2 才增加 `asr.transcribe` / `tts.speak` 节点和控制台；节点变量不得携带 byte[]/base64，真实厂商联调仅单样本，不进 Gatling。
- 双向实时音频、本地 piper/CosyVoice、声纹和音频历史语义留到后续切片；语音按秒/字符独立计费，不自动折算为 token。
- D2 两节点通过纯 Java 共享音频调用组件与 API 保持同一门禁/复验链。`tts.speak` 只写 `audioHandle`，`asr.transcribe` 只写 `transcript`；model 取配置，不发音频 DELTA。句柄绑定 runId，内存 TTL 5 分钟、64 条/32MiB；执行段终态、挂起、异常释放，30 秒扫描过期，关闭后拒绝迟到写入。检查点里的字符串句柄不能恢复音频，恢复段须重新合成。参数、输出与测试详见 `m4-3d-plan.md`。
- 控制台独立一次性音频面板走 D1 API；`audio-roundtrip` 以既有文本输入验证 TTS→ASR 节点，并只返回文本。不增加外部音频工作流入队、音频下载、跨实例字节传输或持久化入口；音频和 browser Blob 不进入会话/附件/记忆。
- M5-A 的 `offline` profile 通过 `OfflineEgressGuard` 强制把 chat 与 embedding 的 HTTP 端点限制在 loopback，关闭云端知识库 bean；本地模型未完成首次成功探活前能力报告为 `unavailable`，不把已配置地址当成可用模型。验证入口为 `script/verify-m5-offline.ps1`，不下载或提交模型文件。
- M5-B 的本地 sidecar 只通过 `LocalAsrProtocol` / `LocalTtsProtocol` 提供厂商无关的请求/响应编码；`AudioConfig` 仅在显式 `mode=local`、loopback endpoint、通道 provider 和协议 bean 均配置时装配 JDK HTTP provider。缺少任一项或 endpoint 非 loopback 时能力保持 `unavailable`，不回退 mock，也不读取协议外的密钥或错误正文。
- M5-C 当前只冻结 `engine/voiceprint` 的纯 Java `VoiceprintProvider` SPI：只支持指定主体的 1:1 `verify`，结果固定为 `VERIFIED` / `REJECTED` / `UNAVAILABLE` / `INVALID_SAMPLE`，并携带固定原因；完成比对时附归一化置信度区间，`SUBJECT_NOT_ENROLLED` 不伪造分数。主体标识由共享边界校验为非空，未配置时使用显式 `disabled` provider。样本只在调用内存中传递，能力与模型版本仅进入诊断元数据，不创建表、不保存向量、不接入实时音频或权限矩阵。HTTP provider 的能力描述只保留模型名，不回显 endpoint 地址。
- D0 新增 52 条测试；全量 687 条、0 失败、0 错误、1 条真实 embedding 校准跳过，离线打包成功。证据见 [`m4-3d-validation.md`](m4-3d-validation.md)；API、节点、真实厂商和负载验证不属于本次 D0 验收。
