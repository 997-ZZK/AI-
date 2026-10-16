# M4-3C：Gatling 压测实施方案

状态：2026-10-12 已完成本轮 mock 验证。S0/S1/S2、同会话快速连发、S4/S5已通过；诊断包635条测试0失败、0错误、1跳过。当前包的S3a同run与S3b不同run均以20秒逐步建连通过1000连接/reader门禁，慢消费者queue16故障演练通过；10月10日1000 VU瞬时起跑失败和635包第一次readFailures=30失败均保留。C 只验证 mock 引擎的并发正确性、背压和资源行为，不给生产容量承诺；D待审。结果见 `m4-3c-validation.md`。

## 目标与边界

- 用独立 Maven 子项目 `script/gatling/pom.xml` 隔离 Gatling 依赖；根项目常规 `mvn test/package` 不引入压测依赖。
- Gatling Java DSL 锁定 `gatling-core-java:3.16.0` 与 `gatling-maven-plugin:4.21.12`。两者版本来自 Maven Central 官方 metadata；依赖已下载到用户 Maven cache，不安装全局软件。
- 所有场景强制 provider/embedding mock，关闭真实 provider 探活、摘要、长期抽取、云知识库、OCR 与语音；另关闭审计持久化/保留期清理、审批扫描和通知，避免后台任务修改已有验证记录。启动参数和环境开关写入 `target/m4-3c/`，过滤所有密钥值。
- 用户、会话、消息使用唯一合成前缀；当前 API 没有删除端点，压测不自行删除数据库数据，报告记录残留合成记录数量。
- 消息提交按实际 API 契约检查 HTTP 200 与响应体 `status=QUEUED`；`workflow_runs` 入库状态为 `PENDING`，与接收响应的状态不同。

## 前置修复

`RunStreamService` 将 `RUN_SUCCEEDED`、`RUN_DEGRADED`、`RUN_FAILED`、`RUN_CANCELED` 标为 terminal，发送后清理连接和 Redis 订阅；`STREAM_ERROR` 保持 terminal。补单测验证终态唯一、连接归零和订阅清理。没有这项修复，1k SSE 场景会把终态连接留到30分钟超时，结果不可用。

## 阶段与硬门禁

| 阶段 | 场景 | 通过条件 |
| --- | --- | --- |
| S0 | 1 VU：建用户/会话、发消息、SSE 首个非空 `DELTA`、唯一运行终态；再做一次取消 | POST=200、SSE=200/text-event-stream、每个 run 恰好一个终态、取消后 run/node/task 收口 |
| S1 | 10 个独立会话各10轮，workers=16 | 无5xx/JSON错误；TTFT、提交延迟、run吞吐、队列/锁等待、JVM/DB/Redis指标形成基线 |
| S1-order | 1个新会话快速连续提交10条消息，期间不等待运行完成；queueCapacity=1000、workers=16 | 10次HTTP200/QUEUED；提交index/clientMsgId/runId逐项对应PG task.seq顺序；无执行段重叠，10个run/node/task全部收口 |
| S2 | 200 个独立会话并发一轮 | 仅作为压力曲线，不把200 VU称为200个同时执行；记录 workers=16、排队和资源饱和点 |
| S3a | 同一个已终态 run 建1000条空闲 SSE 连接，Last-Event-ID=最新seq，每连接从首个 PING 起持续观测至少60秒且收到至少4次 PING | 连接数接近1000、PING无丢失；结束后连接数归零、overflow/failures/lease.lost为0 |
| S3b | 1000个已存在 run 各建一条 SSE 连接，每连接同样从首个 PING 起持续观测至少60秒且收到至少4次 PING | 单run reader成本单独记录，不与S3a合并；同样验证心跳、断开和资源回收 |
| S4 | 小队列容量+人为持锁，单会话灌入超过容量 | 429快速返回；messages、workflow_runs、dispatch_tasks计数不变，clientMsgId可重试 |
| S5 | 最多4个独立会话同时运行30秒 delay，再并行取消；workers=16 | 取消延迟有记录；run/node/task最终收口；取消前另一会话短任务已成功，且4个delay仍在运行 |

任何终态缺失/重复、5xx、非法 JSON、同会话执行段重叠、429残留、取消后孤儿任务、连接不归零、OOM/长GC停顿或数据库连接超时都立即停止该阶段。性能只与同一版本的 S1 基线比较。

S1的每轮先等待终态，只形成独立会话性能基线；质量矩阵中的“同会话快速连发10条”由 `script/verify-load-session-order.ps1` 补证。该脚本只新增1个合成用户、1个会话、10个运行，记录提交序号、幂等键、运行ID与提交时间，复用 `verify-load-evidence.ps1` 核对PG完整快照，再把任务seq与提交顺序逐项匹配；不扩展并发规模，不记录正文。报告保留 `evidence.database.runs`，可交给 `verify-load-events.ps1` 独立检查每个运行的终态事件。

S4/S5 使用 `script/verify-load-tail.ps1`，要求独立mock实例、队列容量2、workers=16。S5固定4个delay即可验证并行取消和其他会话能继续执行；4个runspace全部就绪后统一释放起跑屏障，记录实际HTTP起止区间并要求重叠峰值至少2，不凭4个成功响应声称并行。完整场景保留1个合成用户、6个会话、8个运行，其中4个成功、4个取消，产生4条assistant消息。数据库快照限定该合成用户，Redis锁仅对本轮新会话创建，并用owner条件校验后 `PEXPIRE 1` 释放；不删除数据、不迁移表。

## 指标口径

- TTFT：Gatling 发起 POST 前 `System.nanoTime()` 到首个 `type=DELTA` 且 `data.text` 非空事件；包含提交、排队、Redis、执行和安全审核保留窗口。另列 HTTP 提交耗时、SSE 建连耗时、run 总时长。
- 每轮证据同时保存 `submittedEpochMs` / `terminalEpochMs`；完成run吞吐的测量窗口为最早提交至最晚收到终态，`完成数 / 窗口秒数`。不以Gatling全局HTTP/SSE/check请求RPS替代run吞吐。
- 同会话顺序：以 PostgreSQL `session_dispatch_tasks.seq`、started/finished 时间与消息 `clientMsgId` 对照；不以请求完成先后推断执行顺序。
- 队列等待、锁等待、租约丢失、pending age、SSE/事件失败与连接数读取 Actuator Prometheus 或状态接口；节点耗时从 `node_runs` 快照计算。当前没有自定义 TTFT/run吞吐指标，不能假称已有。
- 同一终态 run 的1000条SSE只测连接与心跳；1000个不同 run 才测每run Redis reader。慢消费者不使用 Gatling `pause` 冒充，另列 raw socket 场景或留到后续故障演练。
- 空闲 SSE 的心跳由服务端统一15秒定时器发送，首个 PING 可能在建连后立刻到达，4次 PING 只覆盖约45秒；首个成功 PING 将 `firstNano` 与 `lastNano` 设为同一 `System.nanoTime()`，后续成功 PING 只更新 `lastNano`，直到 `lastNano-firstNano>=60秒` 且计数至少4。判断与报告只用首尾已确认 PING 时间差，调度空档不计为新的观测证据。首个 PING 等待最多30秒、后续每次最多20秒，失败仍主动关闭连接；不以无检查的 `pause` 证明连接存活。
- S3 仿真要求显式设置 `load.evidenceFile` 为 `target/m4-3c/` 内的绝对 `.ndjson` 路径。每VU在关闭连接后追加一行 `{runId,vu,topology,pingCount,observedMs,failed}`：`observedMs` 为首尾确认 PING 的单调时间差，无 PING 时为 `null`；连接/心跳/关闭失败、计数不足4或时长不足60秒均标为失败，终态快照预检失败也记录失败行。并发追加使用同一进程内锁，不保存事件或消息正文。每轮使用独立路径，保留旧v2产物；成功结果需逐行核对VU数、topology、runId集合、`pingCount>=4`、`observedMs>=60000`与`failed=false`。
- S3结束后运行 `script/summarize-load-sse.ps1 -EvidenceFile <NDJSON> -MetricsDir <sampler目录> -ExpectedConnections 10或1000 -Topology same-run或different-runs -OutFile <尚不存在的独立JSON> -BaseUrl http://localhost:8082`。它复核逐VU证据和一个完整sampler及四份快照、原始`.prom`，要求连接/订阅峰值达到本轮规模，same-run reader峰值恰为1、different-runs reader峰值达到规模，并至少有一条采样同时满足三项规模要求，事件失败/overflow为0；只GET最终events/dispatch状态，连接、订阅、reader、activeWorkers与leaseLost均归零。10连接报告只能标为脚本smoke，通过也不写成1000连接验收通过；检查失败仍保存独立失败报告，不覆盖输入或已有报告。
- `summarize-load-run.ps1` 从每轮证据的 `stage` 校验并推断 S1/S2，S1 run 时长合理性上限120秒、S2上限300秒（两次120秒 SSE 检查及装配余量）。指标名以原始 Prometheus 样本为准；`aiflow_dispatch_lease_lost_total` 首次丢租约才注册，未出现可记录为未注册，出现后必须为0；数据库连接超时、事件失败与overflow同样要求0。每阶段显式指定独立 `OutFile`，避免覆盖前一阶段报告。
- 每条指标 series 记录首/尾导出样本是否覆盖客户端窗口起点/终点。Timer 的 count/sum delta 仅为各标签 series 的最后导出值减首个导出值，报告保留这些端点与覆盖标记；首个导出晚于客户端起点时，明确存在注册/导出前基线缺口，不假设缺失值为0。例如首个 count 已为10、最后为100，delta=90只表示可观测区间的90次，不能据此声称100次运行中只有90次被计时，也不能将90当完整压测总数。

## 运行方式与产物

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -f script/gatling/pom.xml -Pload-test `
  -Dload.baseUrl=http://localhost:8080 -Dgatling.simulationClass=com.aiflow.gatling.SmokeSimulation `
  test-compile gatling:test
```

压测 JVM 通过 `-Daiflow.provider.mock.latency-ms`、`-Daiflow.provider.mock.chunk-delay-ms`、`-Daiflow.provider.mock.chunk-size` 调整 mock；现有 `application.yml` 尚未提供对应 `AIFLOW_*` 环境变量，C 不伪造不存在的变量。引擎启动参数不写入 Gatling feeder，任何 `*_KEY`/secret/password 值都从报告元数据过滤。

本机可用内存需在各阶段前复核。独立引擎堆上限768 MiB，Gatling堆上限256 MiB、直接内存上限128 MiB；Maven串行运行并限制堆。启动器 `script/start-load-engine.ps1` 强制本地mock并关闭Flyway，要求已有V10；`verify-load-smoke.ps1` 补充原始SSE/取消验证，`verify-load-evidence.ps1` 只读核对PG收口与资源回收。Gatling SSE检查使用它的外层 `{event,id,data}`，运行事件正文在 `$.data`，文本路径为 `$.data.data.text`。

S2 使用 `StressSimulation`，200 VU各一轮、workers=16，`load.eventTimeoutSeconds=120` 给排队运行足够的观测窗口；S0/S1默认30秒。超时仍按失败记录。请求并发与实际执行worker数分开记录。1k SSE阶段启动前若可用物理内存不足1 GiB，保留资源证据并暂停该规模，避免内存不足破坏当前基线；不据此声称应用1k容量不足。

源码放 `script/gatling/src/test/java`，构建产物和 HTML 报告统一放 `target/m4-3c/gatling-build` 与 `target/m4-3c/reports`，不在 `script/gatling/target` 留产物。阶段报告、配置快照、Prometheus快照、SSE与数据库核验结果写 `target/m4-3c/`。

完成 S0/S1 并确认指标采集正确后，才进入 S2/S3/S4/S5；如果资源或连接上限阻止1k场景，记录阻断证据和已完成阶段，不把较小规模外推成1k容量。

1k内存门禁未通过时，可以用同一仿真分别运行10条相同run/不同run连接，核实持续心跳与关闭/reader共享行为；这两项仅是S3脚本smoke，不替代S3a/S3b的1000连接验收。复用已经过PG与Redis核验的合成终态run，不额外制造1000条运行。

SSE关闭附近的异常需要单独核验：已提交text/event-stream上的传输IOException不再尝试回写JSON，非SSE或未提交IO错误保留500契约。诊断实例可传 `start-load-engine.ps1 -TraceHttp`，只记录HTTP方法、URI路径、状态、耗时与客户端端口（不记录query、header、正文）；文件随唯一JVM标记保留在target/m4-3c。raw10诊断实例使用五列 `%m|%U|%s|%D|%{remote}p`，其中Tomcat `%D` 是微秒，端口用于对照curl的本地端口；此前四列日志原样保留。访问日志是请求结束记录，单凭条目数不能断言新GET或异步dispatch次数。

独立 raw10 诊断使用 `script/verify-load-idle-raw.ps1`，只读取已核验的 `target/m4-3c/s3-different-smoke-runs.csv` 中10个不同终态run及最新seq。10个runspace经共享起跑屏障，各自启动一个禁用curlrc的本机curl，HTTP/1.1、Last-Event-ID、95秒上限，不重试、不重定向；所有HTTP头和事件正文仅在进程内解析。每个客户端记录一次进程启动、HTTP状态/媒体类型、成功连接数、重定向数、客户端本地端口、退出码、首尾PING时刻与单调时间差；要求HTTP200/text-event-stream、至少4个空PING且首尾间隔至少60秒。95秒到期的curl退出28是预期测试关闭，提前EOF仍记失败。报告保存到独立唯一target目录，异常仍落盘；失败清理只终止本脚本启动的curl进程。将客户端本地端口与只含请求方法/路径/状态/耗时/远端端口的访问日志对照，分辨真实GET和异步错误再次dispatch，不把10条诊断结果外推为1k容量。

## 2026-10-10 续验约定

先恢复已安装Docker与既有PostgreSQL/Redis容器，不重建、不清卷、不执行迁移；独立mock引擎须状态接口就绪且空闲，之后采集新内存门禁。空载内存、引擎启动失败或状态缺失不构成通过门禁，旧报告原样保留。先做10 VU Gatling与五列客户端端口访问日志对照，解释20峰来源，再进入S3a/S3b，不在未确定现象上直接放大规模。

当日feeder必须由PG终态和Redis只读新快照生成，核对最新seq及TTL；24小时保留期意味着昨日CSV不能直接充当新证据。S3a复用1个可读终态run。S3b优先复用1000个可读的不同终态run；不足时，仅通过现有mock API补齐缺少的合成样本，逐批提交/等待收口，再核验PG及Redis并生成独立CSV。此准备不属于性能测量，不删原数据，也不通过手工SQL或迁移补数据。报告保存补齐数量与各runId，不保存正文。

首轮1000 VU同时起跑时，353次终态GET预检TCP连接被拒绝、指标采样超时，报告保持失败。`IdleSseSimulation` 增加 `load.rampSeconds`（默认0=原有同时起跑，范围0–300），非0时在指定秒数内逐步注入全部VU；新S3空闲连接对照采用20秒建连，逐连接仍须确认至少60秒心跳，采样须实际同时达到1000连接/订阅与对应reader拓扑。证据记录注入秒数，汇总和文档明确这只验证逐步建连后的常连规模，不宣称1000同时起跑通过、不把353次失败剔除后改写旧报告。

新报告采用 `20261010` 唯一前缀，保留昨日交付清单与失败产物。1k门禁、仿真、SSE逐连接证据及采样/收口分别核验，失败停止该阶段并保留事实。1k因资源门禁阻断时，可独立验证仅两连接的慢消费者正确性，不据此声明规模容量。

慢消费者诊断使用新 `script/verify-load-slow-sse.ps1` 和独立mock实例，启动器新增可选 `-SseQueueCapacity` 只设置该进程的 `aiflow.events.sse-queue-capacity`，默认仍2048，诊断值16；mock chunk-size=8/chunk-delay=5ms，其余默认安全上限不变。仅新增1个合成用户、1会话、1工作流、1运行；工作流 `start → delay(5s) → llm1 → llm2 → end`，两个LLM各固定7800个合成汉字prompt，不注入历史/记忆/画像，生成流预计不足2000条保留上限。运行不是性能基线。

一个原始TCP socket在connect前设置ReceiveBufferSize=512，只发送一次SSE GET后绝不Read；同run另一个curl持续解析响应。delay期间必须观察2个连接/订阅、1个reader且overflow未增加；正常流要求HTTP200、seq连续、唯一成功终态与EOF，PG运行/节点/任务收口，Redis末尾终态匹配。raw socket保持打开时，要求overflow增量恰1、连接/订阅/reader归零，read/publish failures与leaseLost不增加；再关闭仅本脚本的socket。未诱发overflow记INCONCLUSIVE，正常流也溢出或任一收口不符记失败，不把主动断开、终态回放或Gatling pause充当慢消费者证据。正文只在内存中解析，不落报告；异常/未诱发也保存独立报告，不删数据或改系统/Docker配置。

慢消费者首轮 `m43c-slow-20261010-run1` 在约25.48秒诱发恰1次overflow，正常流seq连续并已收到1780条DELTA，但75秒观测预算到期仍缺终态，按失败记录并取消本轮运行，收尾无残留；该失败报告原样保留。下一轮采用150秒观测加15秒收尾，总预算165秒，curl期限随剩余观测预算；只扩大验证等待窗口，仍用新唯一前缀和空闲独立实例，通过条件、节点60秒超时、安全阈值及合成数据规模均不变。

### S3b 阻塞读取连接复用修复

首次S3b的1000不同run未通过：115条成功、885条提前结束，reader采样峰439、readFailures3176。Redis持续健康、OOM=false、restart=0；采样窗口total_connections_received由1628增至12578（+10950）、rejected_connections=0。Spring Data Redis3.5/Lettuce6.5当前路径在每次 `RedisTemplate.opsForStream().read(BLOCK)` 后释放专用连接，1秒空闲轮询重复建连，增加了本机Docker端口转发的连接压力；连接拒绝的底层位置尚不能仅凭这些数字确定。

最小修复保持每run一个reader和现有事件/游标/有限重试契约，只让每个reader在其生命周期内复用一个RedisConnection做阻塞XREAD。读空后的TTL/快照校验仍走普通共享连接，真实读取故障仍计数、有限重试并通知；重连前释放故障连接，最后退订/总线关闭时中断等待并在finally释放连接，正常停止不计故障。不引入连接池依赖、不修改Redis/Docker/系统限制、不吞错误。先验证复用、故障重开、正常退订释放及现有Redis回放/失效用例，再全量test/package，最后同参数复跑S3b；旧失败报告与旧包另存，不把修复前S3a/S1/S2当作新包回归。

635测试包首次复验达到1000连接/订阅/reader、客户端Gatling全部检查成功，但真实readFailures增至30，整体仍失败；该报告保留。随后加入只记录固定操作名和异常类名的脱敏诊断日志，在新鲜mock feeder上复跑同一20秒ramp，S3b 1000不同run与S3a 1000同run均通过，最终readFailures为0。不能删除首次失败或把一次成功外推为生产容量；零失败门禁、连接复用、有限重试和正常退订语义保持。慢消费者两客户端复验也已通过，详见验证记录的2026-10-12收口章节。
