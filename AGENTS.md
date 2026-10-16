# AIFlowEngine 项目规范

> 上级规范：`D:\work1\AGENTS.md` 对本项目同样生效；冲突时以本文件为准（更具体）。

## 项目定位

LLM 工作流引擎（mini-Dify 方向）：DAG 编排、RAG、模型分级路由、会话与记忆、多模态接入。
当前阶段：**M4-1 / M4-2 / M4-3A / M4-3B 已交付；C 的 S0/S1/S2/顺序/背压/取消、当前诊断包的S3a/S3b逐步建连及慢消费者演练已验证；1000 VU 瞬时起跑失败边界保留；D0/D1/D2 mock 音频链路、第一层离线 provider/协议抽象和第二层离线黄金集已验收；chat 流式响应超时与取消已验收**。第一层结果见 `docs/provider-layer1-validation.md`；第二层 63 条黄金场景及 9 条设施测试通过，全量 975 条、0 失败/错误、6 条按开关跳过，离线打包成功，见 `docs/provider-layer2-validation.md`。chat 流式切片定向 109 条、全量 1134 条（0 失败/错误、6 跳过）及离线打包通过，见 `docs/chat-stream-timeout-validation.md`。本地使用与运维收口已验收，双 PowerShell 启动器门禁各 47/47、真实本机演示各 43/43、失败路径 11/11，统一入口与边界见 `docs/local-ops-runbook.md` / `docs/local-ops-validation.md`。设计与代码同等重要：改代码前先读 `docs/architecture.md`。
M4-3 的范围、切片顺序和验收见 `docs/m4-3-design.md`；B 契约见 `docs/profile-design.md`，不把尚未验证的能力写成已交付。

运行回放与失败定位已验收：只读节点分页/详情与控制台面板，全量 998 条（0 失败/错误、6 跳过），真实 API 23/23、原演示 43/43、浏览器 22 项通过，见 `docs/run-replay-validation.md`。后续维护继续遵守下方回放范围边界。

## 目录约定

| 路径 | 放什么 | 不放什么 |
| --- | --- | --- |
| `engine/graph` | 工作流定义、校验、条件表达式 | Spring Web / 持久化 |
| `engine/node` | 节点 SPI 与内置节点 | 业务逻辑（业务放 `api/service`） |
| `engine/run` | 执行引擎、状态机、Recorder 接口 | 直接依赖 JPA / HTTP |
| `engine/event` | 运行事件、事件总线 | 业务日志 |
| `engine/provider` | 模型 provider 抽象与路由 | 厂商 SDK 之外的重逻辑 |
| `engine/retrieval` | 检索 SPI、分数归一化、后处理链、提示词组装 | Spring Web / JPA / 具体厂商 |
| `engine/ingest` | 文档解析与切片（text / markdown / json / pdf / 图片）；OCR 接口 `OcrProvider`（实现在 `integration`） | 向量化（放 `retrieval` / `integration`） |
| `engine/integration` | 对外 HTTP 适配（embedding、云端知识库、OpenAI 兼容端点） | 引擎算法（算法放 `retrieval`） |
| `engine/tool` | 工具白名单与调用护栏；`mcp/` = MCP 客户端（stdio / Streamable HTTP / 老式 SSE 双端点三种传输、静态 Bearer 与 OAuth 令牌刷新）与配置导入，`skill/` = 本机 SKILL.md 技能库，`audit/` = 调用审计（内存缓冲 + 结构化日志 + 异步落库 tool_audit_events） | 具体厂商业务逻辑 |
| `engine/safety` | 内容安全规则层（长度 / 词表 / 注入模式）+ 流式增量审核（`StreamingSafetyGuard`，保留窗口） | 模型层审核（另立） |
| `engine/budget` | 每用户 token 额度：账本、阈值判定、memory / redis 计数 | 计费结算 |
| `engine/attachment` | 图片附件落盘与 sidecar 元数据 | 图片内容解析（`integration`） |
| `engine/audio` | 厂商无关的 ASR/TTS 端口、共享调用校验、请求/响应、PCM WAV、运行内临时句柄 | Spring Web、持久化、具体厂商 HTTP |
| `engine/integration/audio` | mock 与后续获批的厂商语音适配 | 会话/记忆、图片附件存储、运行快照 |
| `engine/session` | 会话串行队列、会话锁 | 会话业务规则 |
| `engine/profile` | 画像偏好端口、白名单纯规则和聚合口径 | 原消息/偏好正文副本、Web、JPA |
| `engine/config` | 配置绑定（`@ConfigurationProperties` 的聚合注册） | 业务默认值（写 `application.yml`） |
| `engine/persistence` | JPA 实体、仓储、Recorder 实现 | 应用服务 |
| `engine/storage` | 原件留存的对象存储抽象（`ObjectStorage`：MinIO 实现 / 未开启实现 / 装配） | 业务逻辑 |
| `engine/api` | Controller、DTO、应用服务、SSE | 引擎算法 |
| `resources/db/migration` | Flyway 迁移，**只增不改** | 手工改库 |
| `resources/workflows` | 示例/默认工作流定义 | 生产定义（走 API 注册） |
| `docs/` | 设计、路线、数据模型、质量方案 | 临时笔记 |
| `script/` | 本地脚本：环境装配、校准、探形、对照与基准（`sql/` 放造数 SQL，如 ANN 基准的 `script/sql/bench-ann-needles.sql`；M4 起放压测） | 生产脚本 |

> `script/*.ps1` 一律存成 **UTF-8 带 BOM**：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解析，
> 中文注释会吃掉后面的引号，报一堆 `Unexpected token`（四份脚本都曾中招，两边 shell 都实测过）。

## 命名与代码约定

- 包 `com.aiflow.engine.<module>`；类 `PascalCase`；方法/字段 `camelCase`；常量 `UPPER_SNAKE`。
- 表名 `snake_case` 复数；Flyway `V{n}__{snake_desc}.sql`，发布后禁止修改。
- 节点类型 `域.动作`（`start`、`llm.chat`、`retrieval.search`、`llm.vision`、`tool.call`、`memory.recall`、`tts.speak`），注册在 `NodeRegistry`，重复注册启动即失败。
- 工具 id：HTTP 工具用配置里的 key；MCP 工具固定 `mcp.<serverId>.<toolName>`；技能库固定 `skill.list` / `skill.read`。三来源写在同一张白名单（`ToolRegistry`），但开关各自独立、一律受 `aiflow.tools.enabled` 总闸约束。
- 事件类型全大写（`NODE_RETRY`）；API 路径 `/api/v1/...`，统一 `ApiResponse` 包装。
- Java 21（records、switch 表达式、虚拟线程）；不用 Lombok；ID 一律应用侧 UUID；时间一律 UTC `Instant`。
- 引擎核心（`graph/node/run/event/provider/session`）不得依赖 Web 与持久化：通过 `RunRecorder`、`RunEventBus`、`SessionLock` 接口解耦。
- 新依赖一律由 Spring Boot BOM 管理版本；引入前在 MR/提交说明里给出理由。
- 不做未要求的"顺手重构"；改动保持与现有风格一致。

## 构建与运行

- `docker compose up -d`（PostgreSQL 15432、Redis 16379，避开同机其他项目）
- `mvn -q -DskipTests package` / `mvn test` / `mvn spring-boot:run`
- 需要 JDK 21 与 Maven 3.9+（本机安装属于红线操作，先问用户）。
- CI 已获用户授权并落地到 `.github/workflows/ci.yml`：GitHub Actions 使用 Java 21 + Maven cache，执行纯 mock/H2 的 `test` 与 `-DskipTests package`，真实数据库、Redis、MinIO、云厂商和凭证不纳入工作流；失败时上传 Surefire 报告。hosted runner 首次执行需先配置目标 remote 并提交到指定账号。

## 测试约定

- JUnit 5 + AssertJ，类名 `*Test`，每个用例覆盖成功与失败路径。
- 引擎算法（`graph/run/session/provider`）必须有不依赖 Spring/DB 的纯单元测试；持久化与 API 用 `@SpringBootTest` + H2（参考 `api/RagApiIntegrationTest`）。
- 质量门禁：RAG 黄金集在 `retrieval/eval/RagGoldenSetEvalTest`（数据 `src/test/resources/eval/rag-golden-set.jsonl`，明细 `target/eval/rag-report.json`）。门槛与阈值校准口径写 `docs/quality-plan.md`，**先改文档再改门槛**；换 embedding provider 必须重新校准阈值。
- 压测必须使用 mock provider（`aiflow.provider.mode=mock` 可强制），禁止对真实模型压测。
- M4 起引入 Gatling，脚本放 `script/`。
- 检索性能相关的改动必须跑 `script/bench-ann-scale.ps1`（ANN 召回 + 延迟）与 `script/verify-ann-retrieval.ps1`（ANN 与精确逐条对照），
  没有实测数字不许改 `ann.candidates` / `ann.ef-search` 的默认值。
- 错误响应契约（`GlobalExceptionHandler` / `ErrorCode`）改动必须跑 `ErrorResponseIntegrationTest`：**调用方写错的请求一律 4xx**
  （非法 JSON / 路径变量类型不对 400、方法不对 405、Content-Type 不对 415），且**响应与日志都不回显报文内容**（Jackson 的
  原始 message 带请求体前几十个字符）。两个坑写在这里免得重踩：Spring 6 的 `ErrorResponse` 是**接口**，写不进
  `@ExceptionHandler`（注解只吃 `Throwable` 子类，写了直接编译失败），新异常类型要在兜底 handler 的 `instanceof` 分流里补；
  `HttpMessageNotReadableException` 与 `MethodArgumentTypeMismatchException` **没有实现** `ErrorResponse`，走 `legacyClientStatus`。
- 控制台（`static/console.html`）改动没有自动化测试，验收靠真实栈手验：起引擎后用浏览器把受影响的流程跑一遍
  （审批看板至少覆盖 待办 / 批准 / 拒绝 / 事件 四条路径），并确认页面无 JS 报错。
- 审批（挂起 / 恢复）相关改动必须跑 `ApprovalFlowIntegrationTest`（H2 六条）与 `script/verify-approval-flow.ps1`（真实 PG + 真引擎）；
  动通知（`approval/notify`）再加 `ApprovalNotifyIntegrationTest`（桩 webhook 收到 REQUESTED / DECIDED / CANCELED / EXPIRED）与
  `ApprovalNotifyDispatcherTest`。**别让用例依赖后台超时扫描**：测试的 H2 是固定库名，同 JVM 里所有 Spring 上下文共用它，
  别的上下文的扫描器可能先赢下条件更新、由它的 dispatcher 发通知（那个上下文可能没配 webhook）。要验超时路径就在上下文内
  显式调 `ApprovalService.expireDue(未来时刻)`，并把 `waitSeconds` 设长到别的上下文按真实时间扫不到。
  三条语义不许在重构里改掉，且必须有用例钉住：**不重放**已执行节点、恢复前校验工作流版本、挂起期间**不持会话锁**。
  契约改动先改 `docs/approval-node-design.md`。
- 鉴权（`common/auth`）相关改动必须跑 `AuthIntegrationTest`（开启态 9 条）与 `AuthDisabledIntegrationTest`（关闭态 4 条），
  再跑真实栈 `script/verify-auth-flow.ps1`（鉴权开）与 `script/verify-approval-flow.ps1`（鉴权关，回归）。
  **动 `@RequireIdentity` 必须验「关闭态它不生效」**：第一版写成静态注解，关闭态照样拦人，12 条存量用例集体 401 ——
  注解声明的是「这个接口需要身份」，是否需要身份由部署开关决定。令牌**值**只来自环境变量（测试走 `@DynamicPropertySource`
  注入同名属性），比较用常量时间且不提前返回；日志 / 响应 / 文档只出现主体 id 与变量名，不出现值。
- 记忆（`engine/memory` + `session_memories`）相关改动必须跑 `SessionContextProviderTest`、`LlmNodeHistoryInjectionTest`、
  `SessionMemoryServiceTest`、`SessionMemoryIntegrationTest`，再跑真实栈 `script/verify-memory-flow.ps1`（要一个**摘要开着**的实例：
  `AIFLOW_MEMORY_SUMMARY_ENABLED=true`、`AIFLOW_MEMORY_WINDOW_SIZE=4`、`AIFLOW_MEMORY_SUMMARY_CHUNK_SIZE=2`）。
  三条不许在重构里改掉的语义：**摘要失败绝不影响运行状态**（只记账 attempts / last_error）、**折叠是条件更新单赢家**（多实例不许双赢）、
  **原消息不删**（只推进 `summarized_count` 前缀计数）。装配口径的改动必须让 `/memory/preview` 仍然如实反映 ——
  它是真实栈上唯一能断言「模型到底收到了什么」的出口，没有它的断言只能证明「没报错」。
  真实栈脚本两个坑：请求体必须显式转 UTF-8 字节（PS 5.1 会按 ASCII 编，中文变 `?`），`clientMsgId` 必须逐轮唯一（它是幂等键，撞了会被去重成同一轮）。
- 长期记忆（`engine/memory/longterm` + `user_memories` / `user_memory_events`）相关改动必须跑 `MemoryKeysTest`、`MemoryExtractionParserTest`、
  `MemoryMergeTest`、`MemoryRecallBudgetTest`、`LongMemoryThrottleTest`、`LlmNodeLongMemoryInjectionTest`、`LongMemoryIntegrationTest`，
  再跑真实栈 `script/verify-long-memory-flow.ps1 -ExpectExtraction`（要一个抽取开着的实例：`AIFLOW_LONG_MEMORY_EXTRACT_ENABLED=true` +
  `AIFLOW_LONG_MEMORY_EXTRACT_MIN_MESSAGES=1` / `..._EVERY_MESSAGES=1` / `..._MIN_INTERVAL=1s`，并把 `cloud` 也放进 `aiflow.provider.cheap-tiers`
  —— 抽取走「优先便宜档」，不改这一项它会落到 mock 上、回显提示词解析不出 JSON）。
  四条不许在重构里改掉的语义：**归并只看 `supersedes` + 人工优先**（MANUAL 永不被抽取覆盖）、**禁写清单在代码里再过一遍**、
  **预算不够整条丢不截断**、**预览不回写 hits**。第三条落地的纪律：`/sessions/{id}/memory/preview` 必须与真实装配**同链路**
  （它撒谎过一次 —— 长期记忆开着时预览说没有，真实栈脚本才抓到），且**同时校验 `role=system` 与各自标签认块**（长期记忆块也是 system，
  不能把所有 system 算成摘要；用户/助手正文即使以标签开头也仍是普通消息，不能少算 `historyCount`）。

## 文档同步（硬性）

- 画像改动先读 `docs/profile-design.md`，验证默认关闭/GET不建行、版本冲突、重置保留源记录、MANUAL白名单、unknown token及装配预览一致。
  跑 `ProfileIntegrationTest`、`ProfileServiceTest`、`ProfileStatisticsPersistenceTest`、`ProfileStatisticsRepositoryTest`、
  `ProfilePreferenceResolverTest`、`ProfileStatisticsTest`、`LlmNodeProfilePreferenceTest`，再跑 `script/verify-profile-flow.ps1` 与受影响的记忆回归；控制台按上述浏览器规则手验。真实验证只使用合成数据和 mock，报告放 `target/m4-3b/`。

- 改接口/事件 → 更新 `docs/architecture.md`；改表 → 更新 `docs/data-model.md`；改范围 → 更新 `docs/roadmap.md`。
- 先改文档再改代码，不允许代码与文档长期不一致。

## 提交与协作

- 前缀 `feat|fix|docs|refactor|chore` + 中文描述，主题 <72 字符。
- 不直接推 `master`；分支 `feature/<topic>`、`hotfix/<topic>`，走 MR 写明验证命令。

## 当前阶段边界（M4）

- chat 流式响应超时与取消已按 `docs/chat-stream-timeout-design.md` 验收：复用端点 `timeoutMs`，仅修改 `integration/OpenAiCompatibleChatProvider` 和既有 integration 测试；计时器只关闭当前响应流，用户取消不记 provider 故障。定向 109 条、全量 1134 条（0 失败/错误、6 跳过）及离线打包通过，结果见 `docs/chat-stream-timeout-validation.md`。产物使用 `target/ops/chat-stream-timeout-20261016-*`，Maven 由根代理串行执行；不改配置/依赖/schema/凭证/CI，不访问真实厂商，不删除旧产物。
- CI 工作流已获用户授权并新增 `.github/workflows/ci.yml`：Java 21 + Maven cache，纯 mock/H2 测试和打包，失败上传 Surefire 报告，不启用真实设施或厂商。当前仓库尚无首个 commit/remote，hosted runner 尚未执行；约束与本地证据见 `docs/ci-validation.md`。
- 厂商 `extra-body` 解析边界已按 `docs/endpoint-extra-body-design.md` 验收：生产范围仅 `integration/EndpointExtraBody`，测试扩展既有 integration 目录。新增 22 条测试，定向 129 条、全量 1130 条（0 失败/错误、6 跳过）与离线打包通过，结果见 `docs/endpoint-extra-body-validation.md`。产物放 `target/ops/endpoint-extra-body-20261015-*`，不改 YAML/凭证/依赖/schema/CI，不访问真实厂商，不清理旧产物。
- 对象存储超时修复已按 `docs/storage-timeout-design.md` 验收：复用已有 `aiflow.storage.timeout`，仅修改 `storage/MinioObjectStorage` 的客户端构造/校验及 `StorageProperties` 的说明。离线 MinIO HTTP 桩放既有 `api` 测试目录；19 条新增测试、定向 78 条、全量 1108 条（0 失败/错误、6 跳过）及离线打包均通过，结果见 `docs/storage-timeout-validation.md`。产物使用 `target/ops/storage-timeout-20261015-*`，不改配置默认值、依赖、schema、凭证或 CI，不删除旧产物。
- 文档入库错误边界已按 `docs/document-ingest-error-boundary.md` 实施并验收：解析/上传/对象存储与索引失败只保留固定分类，异常 message/cause/suppressed 不进 HTTP、文档失败记录和应用错误诊断。相关 59 条通过，全量 1089 条（0 失败/错误、6 跳过）及离线打包成功；测试 JVM 临时目录位于项目可写的 `target/ops/document-ingest-20261015-temp/`，不关闭 JUnit 清理。新增离线边界测试落既有 `api` / `ingest` 测试目录；MinIO SDK 桩测试复用 `api` 测试目录，不建新目录。独立 JAR、日志与只含统计/哈希的验证快照放既有 `target/ops/document-ingest-20261015-*`；不读取真实凭证、不访问厂商、不改配置/依赖/schema/CI/CD，不删除旧产物。维护时运行文档/OCR/存储定向、错误响应、RAG 端到端回归与离线打包；结果见 `docs/document-ingest-error-validation.md`。
- Embedding 与探活错误边界已按 `docs/embedding-probe-error-boundary.md` 实施并验收，见 `docs/embedding-probe-error-validation.md`：仅修改 `integration/HttpEmbeddingProvider` 和 `integration/EndpointProbe` 的诊断，协议测试沿用既有 integration 测试目录。定向 66 条、全量 1054 条（0 失败/错误、6 跳过）及离线打包通过。产物放 `target/ops/embedding-probe-20261014-*`，不读取真实凭证、不访问厂商、不改配置/依赖/schema/控制台/向量算法或性能参数，旧产物保留。
- 对话 HTTP 错误边界已按 `docs/chat-error-boundary.md` 修复并验收，见 `docs/chat-error-validation.md`：仅调整既有 `integration/OpenAiCompatibleChatProvider` 的错误诊断与对应协议/路由测试，不传播上游错误正文或原始异常链。定向 33 条、全量 1019 条（0 失败/错误、6 跳过）、离线打包通过。离线协议桩与报告使用既有测试目录和 `target/ops/chat-errors-20261014-*`；不改凭证、配置、依赖、schema、控制台或正常执行/SSE语义，旧产物保留。
- 视觉 OCR 错误边界已按 `docs/vision-ocr-error-boundary.md` 收紧并验收，结果见 `docs/vision-ocr-error-validation.md`：`VisionOcrProvider` 的调用失败只允许固定 OCR 分类，不把上游正文、凭证、URL 或原始异常链带入 `OcrException`、文档失败记录或 HTTP 响应；成功转写、`NO_TEXT`、页数闸和 OCR 开关语义保持不变。定向 39 条通过；扩大回归排除既有 Windows `@TempDir` 清理权限问题后 988 条通过；离线打包成功。脱敏验收快照放 `target/ops/vision-ocr-20261014-*`。
- RAG 检索错误边界已按 `docs/rag-retrieval-error-boundary.md` 收紧：`RagRetrievalService` 对可插拔 embedding / retrieval provider 的原始异常只保留固定分类和来源标识，不传播 message、`toString()`、cause 或 suppressed；来源健康检查异常仍按部分降级处理。定向与离线打包结果见 `docs/rag-retrieval-error-validation.md`。本轮不改厂商协议、配置、数据库、索引、阈值或真实调用。
- 运行回放与失败定位已按 `docs/run-replay-plan.md` 实施并验收，结果见 `docs/run-replay-validation.md`。范围限于 `api/RunController` 的节点列表/详情 GET、`api/dto` 的回放 DTO、`api/service/RunQueryService` 和 `persistence/repo/NodeRunRepository` 的只读分页/归属查询，以及现有 `static/console.html` 的回放面板。列表不读取/返回 input/output/error 正文，详情按需展开；`startedAt` 空值排末尾、再以唯一 ID 稳定排序。新增测试落已有 `src/test/java/com/aiflow/engine/api`；合成验收、日志、截图、独立 JAR 和交付哈希快照放已有 `target/ops/`，使用 `replay-20261014-*` 前缀。只使用 mock 与既有 V10 PostgreSQL/Redis，不改 schema/迁移/凭证/CI/CD/依赖，不修改调度、SSE、审批和状态机语义。维护时运行定向查询测试、错误响应回归、全量离线测试/打包，并在真实浏览器验收回放及对话/审批原流程。旧产物与业务记录保留。
- 使用与运维收口已按 `docs/local-ops-plan.md` 实施并验收，后续维护继续遵守本切片边界，只新增本地离线脚本、运行手册和验收记录，不改生产 Java/API/schema/迁移/CI/CD/密钥。`script/offline-engine*.ps1` 放统一 Check/Start/Status/Stop、共用函数及隔离启动 worker；`script/verify-offline-*.ps1` 放启动器门禁与合成端到端演示。脚本均 UTF-8 BOM，兼容 Windows PowerShell 5.1 / PowerShell 7，不引入模块/依赖。`target/ops/` 放按 marker 区分的状态、诊断/演示报告、合成附件和日志，旧产物保留；只允许停止 marker 与进程身份均核对一致的本脚本实例，不停止其他进程，不删除文件或数据。只使用既有 V10 PostgreSQL/Redis，Flyway/DDL 关闭；缺 schema 时停止并说明，不自动迁移。默认仅绑定 127.0.0.1，强制 mock、关闭外部能力/探活，以子进程环境白名单和 classpath 配置隔离本机环境；无真实凭证读取或厂商流量。
- M4-3 拟新增模块的落位：`engine/session` 放调度与租约端口，`engine/persistence/dispatch` 放 PostgreSQL 调度实现；
  `engine/event` 放总线端口与内存实现，`engine/integration/event` 放 Redis Stream 适配；`engine/profile` 放画像口径与偏好规则，
  `engine/audio` 放 ASR/TTS 端口与格式校验，厂商实现放 `engine/integration/audio`。这些目录仅在对应切片批准后创建。
- Gatling 仿真拟放 `script/gatling`，验证报告、脚本输出、合成音频与压测产物统一落 `target/`，不放进 `docs/` 或 `src/`。
  本轮不清理已有产物；清理或删除仍按用户红线执行。
- 第一层离线 provider/协议抽象的规则和验收见 `docs/provider-layer1-plan.md`；HTTP 映射/传输继续放 `integration`、音频放 `integration/audio`，协议桩放对应 `src/test/java`。不引入新依赖；不访问厂商、不读取真实凭证、不新增音频模式/HTTP API/schema；本地协议不是任何厂商的兼容承诺。日志、独立包及只含路径/哈希/统计的快照使用 `target/m4-3d/layer1-20261014-*` 文件名。
- 第二层离线黄金集已获用户批准。新增 `src/test/java/com/aiflow/engine/eval/` 只放黄金集加载/脱敏报告设施与数据驱动验收（上下文、工作流故障、RAG 提示词三组），`src/test/resources/eval/layer2-*-golden.json` 只放版本化合成夹具。命名用 case id，不包含身份/业务数据；场景期望先定再实现，不从实际输出自动生成 gold。报告、日志、独立包和快照使用 `target/m4-3d/layer2-20261014-*`，不清理旧产物。报告只留版本/资源 SHA256/case id/类别/状态/数值，不存提示词、回复、音频、厂商报文或环境属性。新测试默认离线、无需额外开关。现有 RAG 阈值与真实模型校准规则保持原口径；真实模型质量不由 mock 通过率证明。
- D0/D1/D2 验证日志、独立 JAR 和脱敏交付快照放 `target/m4-3d/`，使用带阶段与日期的独立文件名；快照只保存测试状态、文件路径与 SHA256，不保存音频、转写/合成正文、厂商原文或凭证。D0 结论写 `docs/m4-3d-validation.md`，D1 结论写 `docs/m4-3d-d1-validation.md`，D2 结论写 `docs/m4-3d-d2-validation.md`；D2 已由用户批准启动，真实厂商联调仍需另行确认。
- D2 节点落 `node/builtin`，纯 Java 共享音频操作和句柄存储落 `audio`；运行资源生命周期端口落 `run`，Spring 装配与定时过期回收落 `config`。临时音频句柄仅供同一次连续执行段内节点互通，不能进入会话输入、附件、PG dispatch 或重启恢复承诺；终态/挂起/异常释放，TTL 5 分钟、最多 64 条/32MiB、30 秒扫描兜底。控制台一次性音频复用 D1 API，工作流示例使用 TTS→ASR，不新增表或音频下载/持久化入口。
- D1 Controller 延续 `engine/api`，DTO 放 `api/dto`，服务放 `api/service`；仅同步 mock API，不增加数据库或应用音频留存。TTS 成功返回二进制，错误返回 JSON；Servlet multipart 临时解析文件由容器清理，与应用持久化分开记录。核心格式、上传限额、日志脱敏及错误契约必须按 `docs/m4-3d-plan.md` 验证。
- 可以动：引擎核心、会话队列/锁、基础 API、M1-M4 表结构与迁移、检索链路（`retrieval` / `integration`）、工具层（`tool`）、安全与额度（`safety` / `budget`）、记忆（`memory`）。
- 暂不引入：Kafka、独立向量库（Milvus 等）、前端框架、Kubernetes、模型自主选择工具（工具由工作流显式指定）；ASR/TTS 排在 M4 后段（云 API 先行），声纹在 M5。
- 向量列固定 1024 维（`aiflow.retrieval.embedding.dimensions`）：改维度必须同时改 `V*__*.sql`、配置与黄金集，并先改 `docs/data-model.md`。
- `RunStatus` 状态机（PENDING/RUNNING/WAITING_APPROVAL/SUCCEEDED/DEGRADED/FAILED/CANCELED）语义变更必须先改文档；
  `NodeRunStatus` 同理（含 WAITING）。
