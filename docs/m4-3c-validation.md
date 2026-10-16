# M4-3C 验证记录

记录2026-10-09、2026-10-10与2026-10-12的分阶段验证。A/B 已验收；C 按 `m4-3c-plan.md` 执行。所有负载使用 mock 对话与 mock embedding，不把本机结果作为生产容量承诺。10月9日的事实与版本边界保留在前半部分，后续续验另列在文末；当前诊断包的S3和慢消费者结果已收口。D 尚未实施。

## 环境与证据口径

- Windows，i7-8850H（6核/12线程），约16GB物理内存；JDK21、Maven3.9.16、Gatling3.16.0/plugin4.21.12。
- 独立8082实例、768MiB最大堆、16 workers、30s执行租约；正常队列容量1000，S4/S5专用容量2。PostgreSQL16.15与Redis使用已有本地容器和数据卷，Flyway显式关闭，不执行schema变更或迁移。
- `start-load-engine.ps1` 保存唯一JVM标记、配置与日志；关闭真实provider/embedding端点、探活、摘要、抽取、云知识库、OCR、工具/持久审计、审批扫描/通知与鉴权。仅终止标记匹配且空闲的本轮临时引擎。
- 产物统一保留在 `target/m4-3c/`；数据库合成记录与旧失败报告均保留。报告不保存凭证、消息正文、节点输入输出正文或锁owner值。
- TTFT从POST前计时至首个非空DELTA；run吞吐采用最早提交到最晚收到终态的窗口。百分位使用nearest rank。Prometheus统计覆盖完整采样窗口（含预热/冷却），峰值为采样值；调度gauge每5秒刷新，不能假称服务端p99。

## 2026-10-09 修复

1. 四种运行终态发送后关闭SSE、清理connection/subscription，防止终态连接留到超时。
2. Redis最后一个订阅正常退订时，XREAD中断不再误记readFailures；活动读取故障仍保留有限重试与故障通知。
3. 每个dispatch lease用独立生命周期锁协调续期与complete/requeue/失败收口；执行期间允许续期，收口后迟到回调不访问PG。活动续期失败、收口围栏拒绝仍计失租并失效guard，不以忽略失败实现门禁通过。
4. SSE正常断连有时回调原始、本地化的IOException，原先只处理AsyncRequestNotUsableException会漏掉它。只有已提交的text/event-stream传输IO错误不再回写JSON，非SSE或未提交IO错误继续保留500与ERROR；不按错误消息文本匹配。

## 2026-10-09 修复前基线与门禁

| 场景 | 客户端结果 | TTFT p50/p99 | run p50/p99 | 完成run/s | 门禁 |
| --- | --- | --- | --- | --- | --- |
| S0 | Gatling8/8；原始SSE成功/取消均通过 | 仅用于smoke | 仅用于smoke | 不作基线 | 通过 |
| S1：10会话×10轮 | 100/100运行、620/620请求/check | 1162.84/1561.90ms | 2338.50/2837.26ms | 4.2996（23.258s） | PG、Redis事件、资源收口通过 |
| S2：200会话×1轮 | 200/200运行、1600/1600请求/check | 见原始汇总 | 见原始汇总 | 见原始汇总 | **未通过：leaseLost=2** |

S1证据：`s1-final.ndjson`、`s1-pg-final.json`、`s1-events-final.json`、`s1-metrics/`、`s1-summary-initial.json`。S2证据：`s2-final.ndjson`、`s2-pg-first.json`、`s2-events-first.json`、`s2-metrics/`、`s2-summary-first.json`。旧S2的200个Stream均连续、终态唯一且与PG一致；整体报告仍失败，保留失租门禁事实，不把业务成功率等同于阶段验收通过。

S1同会话每轮等待终态，只证明逐轮基线；快速连发10条的提交顺序/共享task seq/无重叠另用S1-order核验。

## 2026-10-09 修复后同版本验证

引擎包来自 `lease-fix-test-package.log`：**622条测试，0失败、0错误、1跳过，test/package成功**；唯一跳过为真实embedding校准。Worker共15条测试，覆盖完成后迟到续期、正在续期/完成互斥、失败收口竞争、活动续期拒绝、围栏拒绝与失租只计一次。两个负载场景使用同一包、相同768MiB堆/16workers/30s租约，不改应用默认值。

该阶段负载包已归档为 `aiflow-engine-leasefix-622.jar`，SHA256为 `6C96B6F809F03277A3B89354D60D2DFB7E92439AF60068CEAC6A408CE7B0F227`。后续仅补SSE传输异常分流，不用新包覆盖上述原始负载证据。

10月9日最终异常分流补修后，`sse-disconnect-test-package.log`记录**628条、0失败、0错误、1跳过，test/package成功**；包含6条 `GlobalExceptionHandlerSseTest` 和7条 `ErrorResponseIntegrationTest`。9个当日PowerShell入口的PS5.1解析与UTF-8 BOM全部通过，报告为 `script-validation-final.json`。

补充raw10入口与五列访问日志后，10个脚本再次通过Windows PowerShell **5.1.22621.3672** Parser与UTF-8 BOM检查，最终报告为 `script-validation-ps51-raw10-final.json`；另保留PowerShell7的独立检查结果，不混用版本。

10月9日最终包当时为 `target/aiflow-engine-0.1.0-SNAPSHOT.jar`，后另存为 `target/m4-3c/aiflow-engine-sseio-628.jar`，SHA256为 `CB4C7F3065F630BCC5AC638345A56B08E341D67BD972156FEB1C9DC11F48470E`。S1/S2、顺序与S4/S5使用前述622测试阶段归档包；当日最终628测试包仅再补SSE异常分流并复验SSE，不声称所有负载均在最终包重跑。该路径之后的重建不改变已归档包和历史验证归属。

| 场景 | 成功运行 | TTFT p50/p99 | run p50/p99 | 完成run/s | 结果 |
| --- | --- | --- | --- | --- | --- |
| S1：10会话×10轮 | 100/100 | 1169.98/2759.40ms | 2296.40/4192.50ms | 3.7645（26.564s） | Gatling620/620，PG27/27，Redis104/104，最终汇总通过 |
| S2：200会话×1轮 | 200/200 | 23573.42/35503.44ms | 24796.00/36115.98ms | 5.1076（39.157s） | Gatling1600/1600，PG27/27，Redis204/204，最终汇总通过 |

对应证据为 `s1-leasefix*` / `s2-leasefix*`；最终关联报告是 `s1-leasefix-summary-final.json`、`s2-leasefix-summary-final.json`。S1/S2采样各59/89条，HTTP和基础设施快照失败均0。两个阶段最终active run/node/task/lease、worker、connection/subscription/reader全0，read/publish failure、overflow、leaseLost全0。

S2采样峰值为16个调度运行段、184个排队任务、200条SSE/reader；Hikari池max=10，active=10/idle=0时pending峰191。数据库连接等待数与调度排队数含义不同。堆使用峰151.53MiB，GC滚动max采样13ms，数据库连接超时增量0。TTFT包含约12批任务在16workers下排队，不能把200VU写成200个同时执行。

本轮修复解决的是正确性门禁。旧/新S1 p99分别1561.90/2759.40ms，新S1最慢10条均在首轮；正式基线仍保留完整100条，首轮分组只用于诊断。旧S1开始采样时已有3次调度，新S1从0开始，预热/缓存/资源条件不同，去掉首轮后新数据尾延迟仍高于旧数据，不能把差异全归因冷启动，也不能以一次对照宣称调优提速。未建立M4-3A改动前的有效负载基线，对照仅覆盖本轮租约竞争修复前后。

S1的queue-wait timer首次导出时count已经10，实测delta为90；汇总明确标记窗口起点基线缺口，未补虚构的0。S2两个等待timer都覆盖起点，count delta均200。lease-lost counter惰性注册，没有导出时标记未注册，最终状态接口的leaseLost=0另作事实门禁。

### 同会话快速连发

`order-final/m43c-order-20261009-final-order-report.json`：在685ms内连续提交10条，不等待运行终态，30/30检查通过；逐项匹配提交index/clientMsgId/runId与共享task seq，无执行段重叠。PG保留1用户/1会话/10运行/10任务/30节点、10 USER和10 ASSISTANT，全部成功。`order-final/events.json` 14/14通过，10个事件流终态唯一并与PG一致。

### 背压与并行取消

`tail-final/m43c-tail-20261009-final-tail-report.json` 38/38通过，`tail-final/events.json` 12/12通过。

- S4：持本轮新会话的Redis锁，容量2接受两条，第3条429在102ms返回；messages/run/task均维持2/2/2，拒绝的clientMsgId没有残留。按owner条件 `PEXPIRE 1` 释放后，两条成功收口；同clientMsgId重试只生成1条USER、1个run、1个task。
- S5：4个30s delay均为RUNNING时，另一basic-chat短会话在1732ms内完成；4个delay仍在运行。4个runspace全部就绪后统一释放屏障，实际HTTP区间重叠峰为4，响应57–62ms。首次PG观测CANCELED为558.22–590.24ms（含轮询/数据库读取开销），全部run/node/task收口耗时605ms。
- 留存1用户、6会话、8运行/任务、20节点、8 USER和4 ASSISTANT；成功4、取消4。4个取消运行无assistant，所有活动状态、资源与失租/事件失败计数为0。

### SSE小规模拓扑与关闭观察

10 VU两种拓扑均收到每连接6次PING，首尾确认PING间隔约74.98秒，90/90 Gatling请求/check通过。相同run的10连接共享1个reader；不同run的reader峰为10。逐连接证据与汇总为 `s3-same-smoke-v3*`、`s3-different-smoke-v3*`，各114个服务端样本，最终所有SSE资源归零。

不同run关闭附近4个样本观测到connection/subscription峰20，不能写成恰好10个物理连接；该阶段仅有10个逻辑VU。Gatling的同名idle-events包含connect/close各10次，不能用该统计断言20个HTTP GET。默认异常断流可在3秒后重连，但截至10月9日的证据不足以确定本次20峰的根因，保留实际峰值与当时未解释的观察。

同run断开后部分heartbeat写入抛原始IOException，旧全局handler尝试将ApiResponse写到已提交text/event-stream，产生No converter二次异常。10月9日最终628包的不同run复验为 `s3-different-sseio.ndjson`、`s3-different-sseio-metrics/`、`s3-different-sseio-summary.json`：10条客户端记录均成功，6次PING、首尾约74.99秒，Gatling90/90、129个指标样本、无采集失败，最终资源与故障计数归零；日志没有ERROR、unhandled或No converter。connection/subscription峰20、reader峰10的观察仍保留，异常分流修复不等于解决额外订阅。

该实例四列HTTP结束日志为 `m43c-sseio-final-20261009-2105-http.log`，每个run出现约100秒与15秒两条500记录；客户端初始HTTP200检查通过，不能把已提交SSE的结束状态当成线上建连返回500，也不能仅以日志条目数证明客户端重连。实例按唯一标记和空闲状态核验后停止，记录为同前缀 `-stop.json`。随后raw10诊断使用五列方法/路径/状态/微秒耗时/客户端端口对照curl，无query/header/正文落盘。截至10月9日1k验收未执行，小规模smoke不代替容量结论。

无重连对照已完成，报告为 `m43c-raw10-20261009-closecheck-run1/raw10-report.json` 与 `raw10-closecheck-summary.json`：10个runspace同步启动10个curl，每客户端只启动一次、成功连接1次、重定向0次，HTTP200/text-event-stream；每条7次PING，首尾89,436.091–89,456.885ms。95秒到期退出28是测试主动断开，未提前EOF。`raw10-closecheck-metrics/` 共134个样本、采集失败0，connection/subscription/reader峰均10，最终归零，overflow/read/publish failures与leaseLost均0。

五列日志 `m43c-raw10-20261009-closecheck-http.log` 恰有10条SSE结束记录，run与远端端口逐项匹配curl本地端口，每客户端仅1条；容器结束状态仍为500、耗时约105.46–105.70秒，引擎无ERROR/unhandled/No converter。这与客户端已收到200是两个不同时间点的事实，保留容器结束状态，不改写为200。raw10未复现20峰，只说明该客户端和时序下没有额外订阅；截至10月9日不能确定Gatling重连或服务端异步时序哪一项导致旧观察。该观察作为当时S3待解释项保留；10月10日端口对照新增的证据及限制见后文。

## 可重复执行的验证入口

| 入口 | 验证内容 |
| --- | --- |
| `script/gatling/pom.xml` | 独立load-test profile，根项目不引入Gatling依赖；HTML报告放target |
| `verify-load-smoke.ps1` | 原始SSE HTTP/Content-Type/seq/终态/EOF，以及运行中取消 |
| `verify-load-evidence.ps1` | 单PG MVCC快照核对run/node/task、消息数量、顺序与资源收口 |
| `verify-load-events.ps1` | 对PG报告逐run只读XRANGE，核对连续seq、唯一末尾终态及PG一致 |
| `sample-load-metrics.ps1` | Prometheus原始/数值采样、硬件与PG/Redis前后快照 |
| `summarize-load-run.ps1` | 关联客户端/PG/Redis/指标，核对完整窗口并生成S1/S2统计 |
| `summarize-load-sse.ps1` | 逐连接心跳/时长、采样规模、实际reader峰与关闭后状态；10连接明确标为smoke |
| `verify-load-idle-raw.ps1` | 10个同步无重连curl，持续心跳与一次连接证据；本地端口对照容器结束日志 |
| `verify-load-session-order.ps1` | 同会话快速提交10条，核对提交顺序与持久task seq |
| `verify-load-tail.ps1` | 容量2的429事务回滚/幂等键重试；4条delay并行取消与独立短会话 |
| `prepare-load-sse-runs.ps1` | 只读PG终态与Redis末尾seq/TTL/终态一致性快照；样本足够才生成全新feeder，不足保留缺口报告 |
| `seed-load-sse-runs.ps1` | 强制mock与Flyway关闭，经API逐批新增缺少的合成样本并等待成功收口；准备数据不作性能测量 |
| `verify-load-slow-sse.ps1` | 同run一个绝不Read的raw socket与一个正常curl，核对overflow隔离、连续成功终态、EOF及PG/Redis/资源收口 |
| `IdleSseSimulation` | 相同/不同run空闲SSE拓扑，持续检查心跳至少60秒；`load.rampSeconds=0`为同时起跑，非0记录逐步注入秒数，外部核对资源回收 |

PowerShell脚本采用UTF-8 BOM并用Windows PowerShell5.1解析；脚本执行策略仅可在当前进程使用 `-ExecutionPolicy Bypass`，不修改系统配置。Maven构建串行执行，显式使用现有JDK和用户缓存，不使用clean删除产物。

## 截至2026-10-09尚未完成的边界（历史快照）

截至10月9日，S1/S2同版本对照、S1-order、S4/S5、SSE异常分流复验与全量回归已经完成。S3的1k场景要求开始前至少1GiB可用物理内存；`stage-resource-gate-leasefix.json`记录该次实际849,186,816bytes（约810MiB），门禁未通过，这不是当前可用内存或应用容量的结论。当日只做10连接的两种拓扑smoke和无重连对照，不停止用户其他程序或修改系统/Docker资源配置，不把小规模结果外推成1k能力。当时计划进入1k前重新采集资源门禁并解释Gatling关闭时20峰；慢消费者raw-socket故障演练不在10月9日已完成声明内，D尚未实施。`delivery-status.json`保留这一日的partial交付快照，不用后续结果覆盖它。

## 2026-10-10 续验（当日阶段记录，后续结果见2026-10-12收口）

### 资源恢复与有效门禁

已安装的Docker Desktop最初未运行，首次临时引擎因PostgreSQL拒绝连接自然退出。启动已有Docker Desktop后，既有PostgreSQL、Redis、MinIO容器恢复；没有重建容器、清卷、迁移或修改Docker/系统资源配置，也没有启动其他项目容器。首份 `stage-resource-gate-20261010-s3a.json`只有空载内存、缺events/dispatch就绪状态，不构成正式通过门禁；`stage-resource-gate-20261010-diag-ready.json`另行说明该缺口，旧报告保留。

引擎就绪且空闲后，`stage-resource-gate-20261010-ready.json`记录可用物理内存3,162,243,072bytes；进入S3b前 `stage-resource-gate-20261010-s3b-ready.json`记录2,621,546,496bytes。两次均达到1GiB要求，且连接、订阅、reader、activeWorkers、事件故障与leaseLost均为0。资源门禁只允许进入验证，不等于场景通过。当日正常S3使用默认SSE队列容量2048；后述慢消费者独立诊断实例仅将SSE队列临时设16，不能把它与调度入队容量混为一谈。

### 五列端口对照与关闭观察

10 VU不同run诊断 `s3-diag-20261010.ndjson`、`s3-diag-20261010-gatling.log`、`s3-diag-20261010-summary.json`通过，Gatling90/90。五列结束日志 `m43c-s3diag-20261010-port-http.log`保存 `METHOD|PATH|STATUS|DURATION_MICROSECONDS|REMOTE_PORT`；`s3-diag-20261010-close-origin.json`逐run配对后确认每run有两条结束记录、两个不同远端端口，合计20条记录/20个端口。在本轮隔离的Gatling负载中，额外订阅伴随额外客户端TCP连接；它们不是同一socket的重复结束记录。具体Gatling FSM重连触发仍未定位。

主采样 `s3-diag-20261010-metrics/`为136条，09:06:36.715Z–09:08:55.920Z，连接/订阅/reader峰均10；补尾采样 `s3-diag-20261010-tail-metrics/`为148条，09:10:00.259Z–09:11:15.079Z，三项已为0。两个独立窗口间约64.34秒无样本，不能拼成完整窗口，也不能声称该次采到峰20。结束日志500与客户端建连时已确认200是不同时间点的记录，不解释为客户端初始收到500。10月9日raw10无额外建连、峰10的对照事实仍保留。

### 当日feeder与缺少样本的准备

Redis保留期为24小时，每轮feeder重新核对PG终态和Redis最新seq/TTL/末尾终态，不能直接复用昨日CSV作新证据。`prepare-load-sse-runs.ps1`仅查近期 `m43c-` 合成终态run，并以每批64个的EVAL_RO读取核验结果，默认要求TTL至少300秒，不导出正文。首次请求1000个不同run时仅631个有效，缺369；`s3-prepared-20261010-1000.json`保持失败且未生成CSV。10个样本的独立准备报告 `s3-prepared-20261010-10.json/.csv`成功。

`seed-load-sse-runs.ps1`在强制mock且Flyway关闭的实例上，以一个新合成用户、每批16个独立会话，经现有API补齐369条运行并GET等待SUCCEEDED。`s3-seed-20261010-report.json`确认369/369成功；随后 `s3b-20261010-feeder.json`与 `s3b-20261010-runs.csv`重新核验1000个有效不同run。没有手工SQL造数、删除原数据或执行迁移；此过程只准备验证样本，不作为吞吐/延迟或并发性能数据。

### 628包的S3结果

下表与首轮慢消费者使用归档628包，先完整保留失败，再增加独立复验报告，不剔除失败VU。

| 场景与注入方式 | 逐VU结果 | 采样与关闭 | 结论与证据 |
| --- | --- | --- | --- |
| S3a：同run，1000 VU同时起跑 | 647成功、353失败，1000条证据 | 70个成功指标样本、1次HTTP超时；窗口未覆盖有效SSE规模，Hikari pending峰533；引擎未崩溃，最终资源归零 | **失败**；`s3a-20261010.ndjson`、`s3a-20261010-gatling.log`、`s3a-20261010-metrics/`、`s3a-20261010-failed-summary.json` |
| S3a：同run，20秒逐步建连 | 1000/1000成功，Gatling8191/8191；每VU最少5次PING、确认首尾最少60,004.9966ms | 200条完整采样、采集失败0，101条样本同时达1000连接/订阅及1reader；连接/订阅峰1905、reader峰1，最终全部0，事件故障/overflow/leaseLost均0 | **指定mock环境下20秒逐步建连后的1k常连验证通过**；`s3a-ramp20-20261010.ndjson`、`s3a-ramp20-20261010-gatling.log`、`s3a-ramp20-20261010-metrics/`、`s3a-ramp20-20261010-summary.json` |
| S3b：1000不同run，20秒逐步建连 | 115成功、885失败，1000条证据；失败为SSE提前EOF/crashed | 197条采样、采集失败0；连接/订阅/reader峰439，未同时达规模；readFailures最终3176，publishFailures/overflow/leaseLost为0；最终连接/订阅/reader/worker为0 | **失败**；`s3b-ramp20-20261010.ndjson`、`s3b-ramp20-20261010-gatling.log`、`s3b-ramp20-20261010-metrics/`、`s3b-ramp20-20261010-failed-summary.json` |

首轮S3a的353次失败全部发生在终态GET预检，TCP `Connection refused`；后续ramp20通过不改写该失败，也不宣称1000同时起跑通过。`IdleSseSimulation`新增 `load.rampSeconds`，默认0仍保留原同时起跑，非0在指定秒数内注入全部VU；NDJSON记录该字段，汇总检查注入profile一致性。没有该字段的旧证据标为未记录，不反填0。

S3a ramp20关闭阶段的1905峰为真实采样事实，不能写成恰好1000个物理连接；逻辑VU和服务端连接数分别记录。S3b故障期间 `s3b-20261010-redis-failure-state.json`显示Redis持续healthy、OOM=false、restartCount=0、memoryLimitBytes=0。基础设施前后快照的Redis `total_connections_received`由1628增至12578（+10950），`rejected_connections=0`；该累计数含采样redis-cli、脚本和容器健康检查等来源，并非引擎专属计数，不据此断言Docker端口转发是唯一底层原因。

### 慢消费者首轮失败与复验条件

独立实例 `m43c-slow-20261010-q16`临时SSE队列16、mock chunkSize=8/chunkDelay=5ms；输入4000/输出8000安全阈值与Redis最多2000条/24小时均不改。只新增一个合成用户/会话/工作流/run，工作流为start→delay5s→llm1→llm2→end，两个LLM各使用固定7800个合成汉字且不装配历史/记忆/画像。raw socket接收缓冲512，只Send一次SSE GET后绝不Read；正常同run curl持续解析。

`m43c-slow-20261010-run1/slow-sse-report.json`记录delay阶段2connection/2subscription/1reader、overflow=0；25.482秒时overflow增加1、仅剩正常1条连接，raw尚未Dispose，正常客户端已收到333 DELTA/seq339。75秒观测预算到时，正常客户端收到1780 DELTA/1788条连续事件，但无成功终态与EOF，报告以 `OBSERVATION_TIMEOUT`失败。取消后PG运行CANCELED且任务收口，无assistant，连接/订阅/reader/worker归零、overflow=1；不能把取消收口或已诱发overflow写成慢消费者验收通过。正常客户端报告的 `SSE_EVENT_UNEXPECTED`来自超时取消收尾，不作为已证明的引擎序列错误。

首轮报告与数据保留。新复验将观测预算改为150秒、另留15秒收尾（总165秒），节点60秒限制、安全阈值、输出规模及所有成功断言不变。该轮最终结果见2026-10-12收口章节；两客户端队列16诊断只验证故障隔离与正确收口，不证明默认队列或生产容量。

### reader连接复用修复与635包边界

`redis-blocking-connection-lifecycle-20261010.json`以归档包中的Spring Data Redis3.5.0/Lettuce6.5.5字节码建立连接生命周期证据：原 `RedisTemplate.opsForStream().read(BLOCK)`每次回调获取并释放wrapper，阻塞XREAD选择的dedicated connection只缓存于该wrapper；现有非池化路径释放时关闭底层连接，因此空闲轮询重复建连。该证据解释连接生命周期，不单独证明本次localhost:16379连接拒绝发生在哪一层；Lettuce关闭内部也可能捕获异常，正常close返回不替代真实栈连接回收观察。

修复保持每run一个reader，只将阻塞XREAD连接改为reader生命周期复用；空读TTL/快照校验仍走普通模板连接，真故障仍计数并有限重试，重开前关闭故障连接，最后退订/总线关闭中断等待并由reader finally释放。ByteRecord用原StringRedisSerializer解码，保留游标、UTF-8正文与错误语义，不引入连接池依赖、不修改Redis/Docker/系统限制。

定向回归**24条通过，0失败、0错误**。全量 `redis-connection-reuse-test-package-20261010.log`记录**635条，0失败、0错误、1跳过，test/package成功**；唯一跳过仍为真实embedding校准，实际Redis集成已开启。`RedisRunEventBusTest`11条覆盖复用/同run共享、byte解码与游标推进、故障连接先关闭后重开、获取连接失败上限、退订竞态、总线停止与关闭失败；5条真实Redis集成保留广播/回放、精确保留、TTL、计数缺口和有限重试契约。

该包另存 `target/m4-3c/aiflow-engine-redisreuse-635.jar`，SHA256为 `0254D005C544C55BD733A137B73ABB3E908F7B4F705F63E0A1EC9F8835AF780F`。622、628归档包与其负载报告不覆盖：旧S1/S2/order/S4/S5仅在622阶段运行，10月10日上述S3与slow首轮在628阶段运行，不把它们写成635包回归。当前诊断包的最终S3与慢消费者结果见下一节；D仍待批准方案。

## 2026-10-12 当前诊断包收口

10月12日先恢复已安装 Docker Desktop 及既有 PostgreSQL/Redis/MinIO 容器；上一次定向测试失败的5条真实 Redis 用例原因为容器退出（`Connection refused`），不是代码断言失败。容器恢复后重新执行定向回归：24条通过、0失败、0错误（RedisRunEventBusTest 11、RedisRunEventBusIntegrationTest 5、RunStreamServiceTest 8）。随后全量 `test package` 为635条、0失败、0错误、1跳过，唯一跳过仍为真实 embedding 校准。

连接复用实现增加了脱敏故障类别日志：只记录固定 operation（`connection-acquire` / `xread` / `idle-snapshot` / `subscribe` / `connection-release`）和异常类名，不记录异常消息、堆栈、runId、Redis key、事件正文或凭证。新包 `target/m4-3c/aiflow-engine-redisdiag-20261012.jar` SHA256 为 `11ADBA38CEEBBCBA52E234C4877F36CCCFCE9753109FF7DAC9685834985B6C12`。旧622/628/635包及其历史负载归属继续保留，当前S3/慢消费者结果均单列在本节。

慢消费者第二轮使用独立 queue16 mock 实例、150秒观测+15秒收尾预算，成功通过：正常curl只建立一次连接，收到1966条连续事件、1954条DELTA、唯一 `RUN_SUCCEEDED` 和EOF；PG为1个成功run/5节点/1条assistant消息，Redis为1966条连续事件、终态匹配、TTL约24小时；raw socket在核验时仍未读取且未释放，overflow恰增1，连接/订阅/reader/worker均已归零。报告为 `target/m4-3c/m43c-slow-redisdiag-20261012-run1/slow-sse-report.json`，停止记录为同前缀 `m43c-slow-redisdiag-20261012-q16-stop.json`。首轮75秒超时失败报告仍保留，不能覆盖。

635诊断包的S3b复验先因旧样本过期而停止：24小时保留后旧CSV仅剩2个可读run；通过强制mock API新增998个独立终态样本，再生成fresh feeder，准备数据不作为性能测量。20秒逐步建连后的S3b通过：1000/1000不同run，Gatling全部检查通过；完整采样208条，同时达到1000连接/订阅和1000 reader的样本77条，峰值为1218/1218/1000，最终连接/订阅/reader/worker、overflow、publish/read failures、leaseLost均为0。报告为 `s3b-diag-ramp20-20261012-summary.json`，fresh feeder为 `s3b-diag-20261012-feeder-fresh.json`。此前635包第一次复验曾出现readFailures=30，整体失败报告 `s3b-reuse-ramp20-20261010-failed-summary.json` 保留；诊断版未复现，不能删除或改写该失败事实。

635诊断包的S3a同run复验也通过：1000/1000连接，Gatling全部检查通过；采样205条，同时达到1000连接/订阅和1 reader的样本87条，峰值为1892/1892/1，最终所有连接、订阅、reader、worker和故障计数归零。报告为 `s3a-diag-ramp20-20261012-summary.json`。这两轮均为20秒逐步建连的mock常连验证，不能外推生产容量，也不改写10月10日1000 VU瞬时起跑失败事实。

至此，C 的S0/S1/S2、顺序、背压、取消、当前诊断包S3a/S3b逐步建连验证和慢消费者故障演练均有独立证据；“1000 VU同时起跑”仍是明确失败边界。D仍待批准方案，真实模型、生产容量和系统级连接上限均不在本轮结论内。
