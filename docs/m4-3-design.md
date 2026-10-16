# M4-3：跨实例执行、画像、压测与语音

> 状态：M4-3A/B 已实现并完成本轮验收，结果见 `m4-3a-validation.md`、`m4-3b-validation.md`；B 契约见 `profile-design.md`。C 已完成阶段性 mock 验证，结果见 `m4-3c-validation.md`；D0、D1 mock API 已验收，结果见 `m4-3d-validation.md`、`m4-3d-d1-validation.md`；M3-KB 仍为待审计划。
> 延续 M4-2 收尾：移除一次性启动器中的明文百炼 key，然后统筹 M4 剩余四项，并入 M3 云端知识库真实联调。

## 1. 当前基线与已完成工作

- M4-1 会话窗口与摘要、M4-2 长期记忆已实现。规划时全量为493条（2026-09-23）；A 最终全量569条、0失败、0错误、1跳过，报告见 `m4-3a-validation.md`。
- B 新增37条，最终全量606条、0失败、0错误、1跳过；真实画像23/23、存量记忆/审批回归与控制台浏览器验收通过，报告见 `m4-3b-validation.md`。
- 本轮已修改 `target/run-engine-8080.ps1`：继承调用方 `AIFLOW_QWEN_API_KEY`，不再给 key 赋值；变量为空时在启动前报错。
  单 UTF-8 BOM；当前 PowerShell 与 Windows PowerShell 5.1 解析零错误；5.1 下缺 key 拦截、调用方变量继承验证通过，验证时用模拟进程入口，未启动引擎。
- Git 为 `main`、无 commit、无 remote，源码仍未跟踪；`target/` 已忽略。后续工作不依赖已有 Git 基线能提供差异证据，应按切片列改动与验证报告。
- 原脚本曾被一个只读子任务误读入工具输出，本地明文已移除；用户明确暂不轮换私人项目 key，本阶段不再推动轮换。

## 2. 顺序与交付条件

| 切片 | 交付 | 先决条件 / 收口证据 |
| --- | --- | --- |
| M4-3A | 持久调度、Redis Stream、租约续期与跨实例取消 | 确认 V9、事件保留策略与本地 Redis 持久化；双实例故障验收通过 |
| M4-3B | 用户画像统计、停用/重置、手工偏好应用 | 确认 V10 与删除语义；聚合口径、偏好预览、关闭态回归通过 |
| M4-3C | Gatling 基线与有证据的调优 | A 的正确性先通过；只用 mock，保存环境与结果 |
| M4-3D | 短音频 ASR / TTS，再接工作流和控制台 | D0、D1 mock 基础与同步 API 已验收；真实适配前确认厂商协议、模型、发音人及调用计费范围 |
| M3-KB | 真实云知识库映射、分数校准与引用验证 | 获得厂商检索协议、知识库标识与环境变量凭证；可与 A/B 并行 |

先补 A：固定 TTL 与进程内队列尚不能支撑路线图里的多实例有序承诺，直接压测只会把挂单和错误统计当作性能问题。
各切片单独更新契约、实现、验证、回填证据；不把五项一次性合并成一个难以核验的改动。

## 3. M4-3A：执行可靠性与事件流

### 3.1 已确认的问题

| 位置 | 当前行为 | 后果 |
| --- | --- | --- |
| `RunCoordinator.process/resume` | 抢会话锁失败直接返回 | 新运行可能永久 PENDING，审批决策后可能无人恢复 |
| `SessionMessageService.submit` | 先落 USER/run，再提交本地队列 | 返回 429 仍留下半成品并占幂等键 |
| `SessionDispatcher` | 各实例独立 FIFO | Redis 互斥不保证跨实例接收顺序 |
| `RedisSessionLock` | SET NX + 固定 30s TTL，仅释放时比较 token | 长任务失锁后仍可能执行和收尾 |
| `InMemoryRunEventBus/RunStreamService` | 进程内缓冲、同步消费者推送 | 重启/跨实例无回放，慢客户端拖住发布者，并发发布可能乱序 |
| 本地 Redis Compose | 无数据卷 / AOF | 仅换 Stream 不能保证容器重建后回放 |

还要覆盖非法 SSE 游标、回放期间连接失败的订阅清理，以及运行中取消目前只作用于持有进程的问题。

### 3.2 持久调度：推荐 V9 新增一张任务表

拟新增 `V9__session_dispatch_tasks.sql`，表 `session_dispatch_tasks`：

| 字段 | 用途 |
| --- | --- |
| `id uuid`、`session_id`、`run_id` | 应用侧任务 ID 与归属 |
| `seq bigint generated ... as identity` | 全局递增，同会话按此序号执行，允许间隙 |
| `kind` | PROCESS / RESUME |
| `checkpoint_id`、`approval_id` | 恢复来源，可空 |
| `dedupe_key`（唯一） | PROCESS 按 runId；RESUME 按 checkpointId |
| `status` | QUEUED / RUNNING / DONE / FAILED / CANCELED |
| `owner_token`、`lease_until` | 领取与条件更新依据 |
| `cancel_requested_at` | 跨实例取消标志，随任务保留，不另放一个有 TTL 的 Redis 标志 |
| 时间列、`last_error` | 排队、执行、结束与诊断 |

- 同会话入队先短暂锁 `sessions` 行，检查共享队列容量，再一起提交 USER / run / task；429 整体回滚。
  “接收顺序”定义为此事务锁内的提交顺序，不猜测两个并发 HTTP 请求在客户端的发送先后。
- 审批最终决策与 RESUME task 在同一 PostgreSQL 事务中提交，包含超时最终决策；仅在 afterCommit 中投递内存任务仍有崩溃丢任务窗口。
- worker 只领取每会话最早未完成的任务，通过 `FOR UPDATE SKIP LOCKED` 与 owner 条件更新竞争。抢 Redis 锁失败时任务仍可重试，不能默默丢弃。
- 挂起时本次执行段 task=DONE、run=WAITING_APPROVAL，并释放锁；后续消息可执行。批准时新建 RESUME task，沿原 checkpoint 的 nextIndex 继续。
- 本地串行队列只作优化，PG 任务才是顺序来源；保留已有 `clientMsgId` 幂等语义，重复请求不得生成第二个 task。
- PG task 租约是持久写入围栏：必须同时满足 owner_token、task=RUNNING、lease_until 大于数据库当前时刻；校验与 run/node/message 写入在同一事务内，共同锁 task 行或通过条件更新领取写权限，禁止先查后无条件写。
  回收器也锁同一 task 行。终态与助手回复收尾共同受该条件约束；task 收尾失败后扫描发现 run 已终态，只收 task，不重跑引擎。

### 3.3 续租、失锁与崩溃边界

- 每 TTL/3 用比较 token 的 Lua 续租；close 先取消看门狗，再比较 token 删除。续租异常或返回 0 都表示失去所有权。
  同时按 owner/status/未过期条件续 PG task 租约；任一租约续期失败都通知运行 guard，Redis 锁不能替代 PG 写入围栏。
- 扩展 `SessionLock.Handle` 的所有权检查，经运行 guard 接入节点开始、重试、DELTA 与收尾。失锁停止新节点、取消当前 future、禁止 fallback/retry 继续调用。
- task、run/节点收尾和助手回复写入必须校验 owner，防止旧实例暂停后恢复写坏状态；失锁以 FAILED/基础设施故障收口，不混作用户显式取消的 CANCELED。
- QUEUED 可安全再领取；已进入 RUNNING 的过期任务不自动重放。目前没有普通节点恢复检查点，工具可能已成功但响应未保存，应标记失败且执行结果不确定。
- 租约与 DB 条件更新不能撤回已发出的 HTTP 副作用。外部副作用的精确一次需要工具侧幂等与节点检查点，不在 A 中虚假承诺。
- 取消标志放 V9 task.cancel_requested_at：QUEUED 取消在同一事务里条件更新 task 与 run 为 CANCELED，领取不再选择它；RUNNING 只先记录取消请求，执行实例以有界周期刷新 guard，在节点边界与 DELTA 发送前检查并收口。
  WAITING_APPROVAL 继续走既有 run/审批条件更新；恢复任务先验证 run 仍可恢复，取消与恢复只能一个赢。无需新增 workflow_runs 列。
- 保留审批三条既定语义：不重放已执行节点、恢复前校验工作流版本、挂起不持会话锁。

### 3.4 Redis Stream 与 SSE 契约

- 保持 `RunEvent.seq` 和 SSE 十进制 `id`，每 run 一条 Stream；Lua 原子分配 seq、XADD `<seq>-0`、裁剪并设置 Stream TTL。同 run 的 Redis key 使用同一 hash tag。
- 建议保留最多 2000 条、距最后事件 24 小时；seq 元数据不随 Stream TTL 过期，长期审批恢复不能从 1 开始。seq 不含正文，先保留；后续清理另定生命周期，不隐式删库。
- mode 默认 `memory`，双实例验证显式用 `redis`。Redis 模式故障不偷偷切回 memory，避免同 run 出现两套序号。订阅读失败保留 cursor 后有限重试。
- 发布失败抛专用 EventPublishException；在引擎和审批所有发布入口统一隔离该异常，不伪造成功 seq，不把业务成功改成失败，也不能遗留 RUNNING 的 run/node/task。
  Redis/SSE 缺失计数和总线状态必须可查；订阅确认存在缺失时返回回放不可用，已建流则发不带运行 seq 的 STREAM_ERROR 控制通知后关闭，客户端读取 PG 快照。
  事件故障与执行失租分开：失租必须停止执行，事件发布故障仍按 PG 事实收口。验收明确注入 RUN_STARTED / NODE_STARTED / DELTA / 终态 publish 失败。
- 用普通 XREAD 广播，不用 consumer group 分摊事件。同实例同 run 共享 reader；回放到实时之间必须有连续 cursor / watermark，不能先 XRANGE 再订阅留下窗口。
- 每个 SSE 连接有独立有界发送队列；慢客户端满队列后断开，让它带游标续传，不阻塞发布者或无限占内存；连接回放失败也必须清理订阅。
- 缺省游标为 0；非法、负数、超过最新 seq 返回 400；`fromSeq < firstRetainedSeq - 1` 或 Stream 已过期时明确返回回放不可用，并提示读取 run 快照，不能当成完整空流。
- 审批事件在事务提交后发布，避免 Redis 异常回滚决策、或者流里出现最终回滚的决策。PG 与 Redis 尚不原子：提交后崩溃仍可能缺事件，run/审批快照是状态事实来源；不承诺事件精确一次，不顺带引入 outbox 表。
- 本地 Compose 增加 Redis AOF 与命名数据卷；应用重启、Redis 普通重启、容器重建分别验证。AOF 的 fsync 策略有最后一段写入丢失边界，不能说所有主机故障都零丢失。

### 3.5 A 的验收与观测

拟新增 `script/verify-distributed-flow.ps1` / `verify-redis-events.ps1`，报告落 `target/m4-3/`：

1. 双实例交替提交同会话 10 条，按 task seq 对照执行段；无重叠、无 PENDING 孤儿，跨会话仍能并行。
2. 另一实例批准/取消；挂起时下一条可完成，恢复不重放前置工具；重复幂等键和重复决策只生成一个 task。
3. 小 TTL 跑超过 3 倍 TTL；替换锁 token 后旧 owner 不能继续推进/覆盖收尾。
4. 杀掉 QUEUED 所在实例后能推进；RUNNING 崩溃不自动重复工具；run 已终态但 task 未结束时只收 task。
5. 队列容量溢出返回 429，USER / run / task 均无残留；恢复任务不因普通消息背压被静默丢弃。
6. A 发布/B 订阅；断线到另一实例续传、并发发布/订阅、缺口/非法游标、慢消费者、Redis 故障和重启均有断言。
7. 保留审批、鉴权关闭态、短期/长期记忆回归；共享 H2 测试不要依赖其他上下文的后台扫描器。

补指标：queue wait、lock wait、lease lost、pending age、reader/connection count、SSE overflow、event publish failures。
核心测试仍不依赖 Redis；Lua/Stream/跨实例顺序必须在真实 Redis + PostgreSQL 上验证，不能靠 mock 声称通过。

## 4. M4-3B：画像与显式偏好

- FACT/PREFERENCE 的真值仍在 `user_memories`；画像不再复制偏好正文，也不推断敏感属性。
- 已新增 `V10__user_profiles.sql`，只存 `user_id`、`aggregation_enabled`、`statistics_since`、`timezone`、`version` 与时间列；无原文、无统计 JSON 快照。
  聚合按最近 30 天与 `statistics_since` 中较晚者实时查询，无缓存；时区为合法 ZoneId，默认 UTC，与已有 UTC 时间一致。
- 已实现接口：GET `/api/v1/users/{id}/profile`，PUT `.../profile`（启用/时区），DELETE `.../profile`（清空视图并停用），GET `.../profile/preview`（偏好与应用解释）。写接口必须提供匹配的 `expectedVersion`，旧版本返回409。
  默认关闭，查询不自动启用；再次启用从新 statistics_since 起算。DELETE 停止聚合及画像偏好应用，版本递增；原运行/审计与长期记忆保留，不能叫“删除所有个人数据”。

| 指标 | 口径与覆盖边界 |
| --- | --- |
| 运行/工作流分布 | 按 workflow_runs.id / created_at；不按会被审批恢复覆盖的 started_at；WAITING_APPROVAL 不算成功 |
| 工具使用 | 持久审计按 SUCCESS / REPLAYED / FAILED / DENIED 分列；复用不是新调用；返回审计关闭/保留期/丢写覆盖情况 |
| 时段 | created_at 转画像时区；不把 UTC 小时当用户当地小时 |
| 已记录的对话 token | 按每条 llm.chat/llm.vision node_run.output.tokensUsed 汇总，分 mock/真实；缺失为 unknown，不能填 0 |

token 统计不含摘要、长期抽取、embedding 或未留 output 的失败/取消调用，不是全量消费账单或金额；当天额度继续单独查预算接口。
第一版只有“工作流分布”，没有可靠语义话题标签，不把工作流名称包装成话题分类。

偏好只使用 MANUAL + PREFERENCE 的枚举白名单（规范 key `response-style`、`model-policy`，写入与查找共用 MemoryKeys.normalize）；明确手工设置才影响风格或 cheap-tier 优先。
预览与真实应用共用口径，并返回来源；不从工具频次猜模型档位，不越过能力约束、熔断、硬额度、工作流禁止项与工具权限。
关闭画像时保留 M4-2 长期记忆行为，不把旧偏好当作已删除。页面必须分别说明“画像停用”和“长期记忆条目删除”。

现有主体令牌没有 Subject→userId 归属映射；单加 RequireIdentity 不能实现“只看本人”。B 延续本地开发边界，不宣称支持真实多用户隔离；生产化访问控制另立方案。
验收覆盖窗口、时区、审批恢复计数、工具复用、未知 token、停用后不重生、偏好优先级、关闭态及装配预览；控制台按项目规则真实栈手验。

## 5. M4-3C：Gatling（已批准实施，方案见 `m4-3c-plan.md`）

- 独立 Maven 子项目 `script/gatling/pom.xml` 的 `load-test` profile；仿真放 `script/gatling`，常规根项目 mvn test/package 不带入压测依赖。锁定 Gatling Java DSL 3.16.0 与 Maven plugin 4.21.12，不安装全局软件。
- 强制 mock 对话与 embedding，关闭真实探活、摘要、抽取、语音和云知识库；报告保存这些开关，不能只口头声称零真实调用。
- 场景沿用 quality-plan：1k SSE、200 独立会话、同会话 10 条、队列 429、慢节点、取消；先 smoke，再逐级加负载，记录硬件和配置，数值是测试目标而非已达能力。
- TTFT 从发送 POST 到首个非空 DELTA；HTTP 200提交延迟与 SSE 长连接时长另列。输出吞吐、错误率、p50/p99、排队/锁等待、池占用、堆/GC与租约丢失；慢消费者raw-socket场景单列未验边界。
- 保留 A 改动前的有效基线与改动后对照；若旧实现无法正确完成场景，应标失败，不能拿少执行/漏事件的结果说更快。
  再基于瓶颈决定调优，每次只改变能解释的参数，不直接照抄 1k/200 为生产容量承诺。
- 现有消息提交实现返回 HTTP 200（响应体接收状态为 `QUEUED`，入库运行状态为 `PENDING`），压测以代码契约为准；运行终态 SSE 需要先标记 terminal 并清理订阅，否则连接会保留到超时，不能进入1k连接场景。

## 6. M4-3D：语音最小链路（D0、D1 已完成，D2 实施中）

- 优先确认百炼产品，但不能假定现有 qwen 对话网关支持 ASR/TTS；真实适配照官方语音协议，不猜路径。
- 独立 `AsrProvider` / `TtsProvider`，不塞进 ChatProvider。现有 AttachmentStore 只收图片，第一版音频直接传字节、不持久保存，TTS 直接返回音频。
- 先短音频上传→同步转写，以及短文本→同步合成；建议首版 WAV/单声道/16kHz PCM/最多 30 秒，最终限额取厂商协议与本地限制中较严者。
  控制文本长度/大小/时长，按文件头校验；超时/错误脱敏，默认关闭，未配置时能力报告如实显示不可用。
- 基础端点验证后接入 `asr.transcribe`、`tts.speak` 节点与独立控制台面板，使用 run-scoped 内存音频句柄完成一条 TTS→ASR 样本闭环，不做双向实时音频、本地模型或声纹。句柄不进异步任务输入、数据库、会话/记忆或浏览器持久存储。
- 语音通常按秒/字符计费，独立限额，不强行换算成 token；真实调用仅单样本联调，不参与 Gatling。
- 具体阶段、端点契约、错误脱敏、能力报告和测试边界见 [`m4-3d-plan.md`](m4-3d-plan.md)。D0 的独立端口、格式校验、mock provider、能力报告和 mock 契约测试已交付；D1 mock 同步 API 已验收，803 条全量回归与独立打包证据见 [`m4-3d-d1-validation.md`](m4-3d-d1-validation.md)。D2 节点/控制台按该方案实施；厂商 HTTP 协议桩和真实单样本联调仍需另行确认。

## 7. 并入 M3：真实云知识库

- 当前 HttpKnowledgeBaseProvider 是自定义 POST /retrieve，不等于任何厂商原生协议。先补厂商、URL/path、鉴权形态、dataset/index id 与官方文档，再运行 probe-cloud-kb.ps1。
- 只用合成语料探形，不开启 ShowKey；探形脚本会显示原始请求/响应，不用公司文档试探协议。
- 真正映射 raw score / 正文 / 文档与切片标识 / 页码与顺序，归一化与阈值重新校准；缺 score 不能通过 primitive double 默认为 0，非有限值直接失败。
- 当前缺 chunk_index 时用结果数组序号伪造顺序，AdjacentMerger 还只按 docId 分组。联调切片必须禁止未知顺序合并，并隔离不同 source，防止不相邻或跨源正文误拼。
- 厂商契约桩测试 + 真实一轮检索/引用断言 + RAG 黄金集；动到检索性能按 AGENTS 另跑 ANN 两条脚本。没有厂商参数时如实标未联调，不用 mock 报告替代真实证据。

## 8. 批准边界与下一步

启动器整改与规划已完成；用户已明确批准 M4-3A 并要求逐步实施，该授权包含 V9、Redis 持久化和 2000 条/24h 事件保留策略，无需重复确认 A。

本次实施范围：V9 调度表（含 cancel_requested_at）、双实例执行与取消契约、Stream 2000 条/24h 自动保留策略、Redis AOF/命名卷及接口游标错误语义。
用户后续“按照计划推进”确认 B 的 V10 与画像停用/重置计划；具体契约见 `profile-design.md`。Gatling 项目依赖和语音真实调用分别在对应切片确认，不要求现在一次性授权全部。
不修改 .env 或凭证值，不安装全局依赖，不 push，不发布；真实删除/清理既有产物仍需用户授权。

A 的具体 architecture/data-model/approval 契约、V9 实现与本轮验证已完成；B 的 V10、画像统计/逻辑重置、偏好与控制台验收已完成。验收记录见 `docs/m4-3a-validation.md`、`docs/m4-3b-validation.md`。每切片交付列实际验证命令和 target 报告。
后续无需重新粘贴旧对话，按本方案与既有记忆契约继续。
