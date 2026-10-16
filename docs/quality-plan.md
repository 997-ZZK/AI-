# 质量方案：压测、评测与故障演练

## 1. 压测（M1 设计 / M4 执行）

M4-3 的执行顺序、正确性前置条件和压测交付见 `docs/m4-3-design.md` §3/§5 与 [m4-3c-plan.md](m4-3c-plan.md)。A/B 已交付，结果与限制见 [m4-3a-validation.md](m4-3a-validation.md) 与 [m4-3b-validation.md](m4-3b-validation.md)；C 的已完成阶段与S3未验收边界见下方，D 的语音尚未实施。下方并发规模是场景目标，不是当前已验证的容量。

### 1.1 前提：mock provider

- 强制 mock：`aiflow.provider.mode=mock`（ProviderRouter 会过滤掉真实 provider）。
- mock 可配：首 token 前延迟（`latency-ms`）、流式分片间隔（`chunk-delay-ms`）、分片大小（`chunk-size`）。
- 禁止对真实模型做压测：烧钱且受 rate limit 干扰，测不出引擎瓶颈。

### 1.2 场景矩阵

| 场景 | 目标 | 观察指标 |
| --- | --- | --- |
| SSE 常连 | 1k+ 空闲连接 + 心跳 | 连接数、内存、GC、心跳延迟 |
| 并发 run | 200 并发独立会话执行 | 吞吐、P99 首 token（TTFT）、错误率 |
| 同会话并发正确性 | 同会话快速连发 10 条 | 执行顺序、无并发写坏 |
| 会话队列背压 | 单会话灌入超过队列容量 | 429 快速失败、无 OOM |
| 慢节点拖尾 | delay 节点 30s | 超时取消、锁/队列不阻塞其他会话 |
| 取消风暴 | 批量取消运行中 run | 取消延迟、资源回收 |

### 1.3 指标基线与工具

- 指标：TTFT p50/p99、run 吞吐、节点耗时分布、会话队列等待、锁等待、DB 连接池饱和点、JVM 堆/GC、SSE 连接数。
- 工具：Gatling（Java 原生，与栈一致；M4 引入，脚本放 `script/`）。
- 准入：任何并发相关改动，需附一次对照压测（前/后）。

### 1.4 M4-3C 本轮结果（2026-10-09）

完整事实、命令入口和边界见 [m4-3c-validation.md](m4-3c-validation.md)。10月9日归档包全量回归628条，0失败、0错误，1条真实embedding校准跳过；10月12日诊断包全量回归635条，0失败、0错误，1条真实embedding校准跳过，打包通过。当前包的S3a/S3b逐步建连1000规模和queue16慢消费者演练均通过；1000 VU瞬时起跑失败边界仍保留。
S1的100条、S2的200条运行及PG/Redis事件门禁均通过；修复前S2的leaseLost=2失败报告保留，修复后为0。
同会话快速连发10条顺序/无重叠通过；容量2的429回滚/幂等重试及4条delay并行取消通过，最终无活动run/node/task/lease或SSE资源。
1k SSE仍未验收：可用物理内存约810MiB，低于方案约定1GiB门禁；10连接拓扑smoke只验证脚本，不证明1k容量。
最终包的无重连curl对照10/10通过、7次PING首尾约89.44秒、连接/订阅/reader峰10且最终0；Gatling关闭时峰20未确定原因，作为待解释项保留。容器断流结束500与客户端初始200分别记录，不把小规模或关闭记录解释为容量结论。
200VU测试配置16workers，调度排队峰184；DB池active峰10/pending峰191是连接等待，不能混作同时执行数。正式统计保留全100/200条，不剔除首轮改善p99，也不以本次单轮前后差异宣称调优提速。
Timer增量只覆盖各序列首个到最后导出的样本，惰性注册造成的起点缺口单列；不伪造基线0或服务端百分位。

## 2. 评测（M2 起）

### 2.1 RAG 黄金集

- 位置：`src/test/resources/eval/rag-golden-set.jsonl`（语料与问题同一文件，单文件是唯一事实来源；早期草案里的 `docs/eval/` 不再使用）。
- 规模：9 篇文档 / 66 条问题 = 55 条可答（事实型 + 5 条多跳）+ 11 条不可答（5 条域内「公司派人问不到」+ 6 条域外）。拒答能力必须两类都覆盖：只测域外问题会把拒答率虚高。
- 判定口径：可答题按 `docId` + 关键字命中召回切片（片段可能被相邻合并，所以不按精确 chunkId 判定）；命中窗口 = 最终进入提示词的 top-5。
- 指标与门槛（`RagGoldenSetEvalTest` 硬断言，低于门槛即失败，避免指标悄悄退化）：

| 指标 | 含义 | 门槛 | mock embedding 基线（校准点） |
| --- | --- | --- | --- |
| recall@5 | 期望切片（gold 条目）出现在 top-5 的比例 | >= 0.85 | 0.923 |
| MRR | 首个期望切片排名倒数的均值 | >= 0.70 | 0.918 |
| 拒答正确率 | 不可答问题在阈值过滤后无切片残留的占比 | >= 0.80 | 0.909 |

- 阈值校准：mock embedding（确定性哈希，只对词面重叠敏感）的余弦量纲比真实模型低，直接套生产 `min-score=0.35` 会把大量可答问题误拒。门禁用校准后的 `MIN_SCORE = 0.15`，报告里的 `thresholdCurve` 给出 0.00~0.35 各点的 recall / MRR / 拒答 / 可答为空数，**改阈值必须看曲线并同步本表**。
- 运行：`mvn test -Dtest=RagGoldenSetEvalTest`；明细报告写 `target/eval/rag-report.json`（含每条问题的命中排名、返回切片与分数、阈值曲线）。
- 触发时机：切片参数、embedding provider、归一化与裁剪参数、提示词模板变更后必须重跑并对比。
- 已知局限（M3 处理）：mock embedding 无法区分「公司派人问公司外的事」这类域内不可答问题与真答案（词面重叠本来就高），域内拒答依赖真实模型；换真实 embedding 后需重新校准阈值并更新本表。
- 换真实 provider 的重校准流程（M2 已备好脚手架）：
  1. 跑校准用例（provider 必须先起，Windows PowerShell 下 `-D` 必须加单引号；云端网关直接用 `script\run-cloud-embedding-calibration.ps1 -BaseUrl ... -Model ...`）：
     `mvn test -Dtest=RagRealEmbeddingCalibrationTest '-Daiflow.eval.embedding.mode=http' '-Daiflow.eval.embedding.base-url=http://localhost:11434/v1' '-Daiflow.eval.embedding.model=bge-m3'`
  2. 探活失败、或维度不等于 1024 时直接失败，不会静默跳过（`vector(1024)` 是列定义，维度不对连入库都过不去）
  3. 报告写 `target/eval/rag-report-<providerId>.json`，含完整阈值曲线与每条问题的失败明细。**不要沿用 mock 的 `MIN_SCORE=0.15`**：真实模型对同义改写也给高分，余弦分布整体上移，低阈值下「低相似度丢弃」会失效
  4. 取「仍满足全部门禁的最大阈值」写入 `aiflow.retrieval.embedding.min-score`（阈值是**向量分布**的属性，跟着向量模型走，所以挂在 embedding 配置块里；全局 `aiflow.retrieval.min-score` 继续留给 mock 量纲），并把新曲线与 §2.1.1 的记录一起更新
  5. 密钥只从环境变量 `AIFLOW_EVAL_EMBEDDING_API_KEY` 读：不进报告、不进日志、不进仓库
- 向量化分级链（`aiflow.retrieval.embedding.tiers`）不改变校准口径，但改变一件事：**校准时用的档位必须与线上生效的档位同模型**。启动期会强制校验各档 `model` 一致，不一致直接启动失败 —— 云端 bge-m3 与本地 bge-m3 是同一向量空间，可以互为兜底；换成别的模型就是另一个空间，检索出来的分数没有意义。
- 真实 provider 校准已经跑过一次（2026-09-20，本机 Ollama + bge-m3，见 §2.1.1）：mock 基线（0.15 / 生产 0.35）与 bge-m3 校准点（0.50）现在是**两个各自成立的量纲**，切换 provider 时阈值必须跟着换。云端档只要有一个 OpenAI 兼容网关地址 + 密钥即可跳过下载直接校准，接入步骤见 README「云端档接入」，封装脚本 `script/run-cloud-embedding-calibration.ps1`（密钥只从环境变量读）。
- mock 门禁与真实校准是互补的两层，不能互相替代：前者每次 commit 跑（离线、确定性、零成本，防切片 / 裁剪 / 排序退化），后者只在换 embedding provider 时跑（防阈值错配）。
- 换 provider 之后必须重建索引（校准的前置条件）：V3 起 `document_chunks.embedding_model` 记录向量来源，检索按模型隔离 —— 索引是别的模型建的时候检索会**显式失败**并提示 `POST /api/v1/kbs/{kbId}/reindex`，不会静默返回空结果。所以顺序是：改配置 -> `reindex` -> 再跑校准。校准用例直接喂内存语料、不读库，不重建也能跑，但那样量出来的阈值和线上链路对不上。

### 2.1.1 真实 embedding 校准记录

| 日期 | provider | 生效阈值 | recall@5 | MRR | 拒答正确率 | 报告 |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-09-20 | mock（确定性哈希） | 0.15（门禁）/ 0.35（生产配置） | 0.923 | 0.918 | 0.909 | `target/eval/rag-report.json` |
| 2026-09-20 | `http-embedding:bge-m3-cpu`（本机 Ollama，1024 维） | **0.50** | 0.9692 | 0.9545 | 0.9091 | `target/eval/rag-report-http-embedding-bge-m3-cpu.json` |
| 2026-09-22 | `http-embedding:qwen3.7-text-embedding`（云端百炼，1024 维） | **0.45** | 0.9846 | 0.9818 | 1.0000 | `target/eval/rag-report-http-embedding-qwen3.7-text-embedding.json` |

bge-m3 的阈值曲线（节选，完整曲线在报告里）：

| minScore | recall@5 | MRR | 拒答 | 可答为空 |
| --- | --- | --- | --- | --- |
| 0.35 | 1.0000 | 0.9773 | 0.6364 | 0 |
| 0.45 | 0.9846 | 0.9727 | 0.8182 | 0 |
| 0.50 | 0.9692 | 0.9545 | 0.9091 | 2 |
| 0.55 | 0.8923 | 0.8818 | 1.0000 | 6 |

为什么不是曲线上的最大值 0.55：0.55 拒答 1.0、可答为空 6 条（11% 误拒）；0.50 拒答 0.909（11 条里漏 1 条）、可答为空 2 条（3.6%）。**第二个语料给出了判断依据**（见下）：在 `samples/kb/`（短文档、单片多主题）上，0.55 把「忘记打卡怎么办？」这种明确可答的问题过滤成零命中（最佳候选 0.523），0.50 则能召回。误拒在用户侧是「明明有资料却说没有」，比放过一条噪声更伤。底线仍然是 **0.35 以下不行**：那是 mock 量纲的默认值，在真实向量上「低相似度丢弃」直接失效（同语料实测：无关问题会捞回 0.38-0.47 的噪声切片）。

### 2.1.2 阈值是语料相关的（2026-09-20 第二个语料的发现）

用 `samples/kb/` 的 8 篇文档（11 个切片）复测同一套阈值，暴露了两件黄金集看不出来的事：

1. **短文档会把多个主题合并进一个切片**：`01-员工手册` 全文 432 字（目标切片 400 字），5 个小节合成 1 片，任何单主题提问的余弦都被其他主题稀释 —— 「忘记打卡怎么办？」与正确切片的余弦只有 0.523，而问「年假有几天？」是 0.623。
2. **引用定位会粗**：同一个切片上，`sectionPath` 记的是首节（`员工手册 > 工作时段`），而问题问的是「打卡与异常」。切片粒度不足时引用标签只能定位到片，不能定位到节。

结论：阈值不只是模型属性，也是**语料结构**属性（文档长度分布、每片主题数）。换语料后如果出现成批误拒，先看切片粒度（`TextChunker` 的 target 400 / 单片多主题），再动阈值；不要为了迁就一个语料把阈值一路降到 0.35 以下。

定下来之后用这套语料做了 11 条查询的验收（8 条可答 + 3 条不可答），结果全部符合预期：可答问题全部召回（首位命中 0.52~0.78，期望文档排第一的 7/8），不可答问题零命中；唯一不符合预期的一条是噪声排序（问「忘记打卡」时「SSE 断流」片 0.529 压过正确片 0.523），属纯向量检索的固有噪声，要根治得上混合检索（BM25 + 向量，M4 待办）。完整分数矩阵与复现命令见 `samples/kb/README.md`。

复现（PowerShell，`-D` 必须加单引号）：

```powershell
mvn test -Dtest=RagRealEmbeddingCalibrationTest '-Daiflow.eval.embedding.mode=http' '-Daiflow.eval.embedding.model=bge-m3-cpu'
```

**为什么是 `bge-m3-cpu` 而不是 `bge-m3`**：同一份权重，只是用 `ollama create` 派生了一个带 `PARAMETER num_gpu 0` 的别名。本机（Quadro P1000 / Pascal + 16GB 内存）上，Ollama 的 Windows CUDA 路径会禁用 mmap、把 1.1GB 模型整块读进内存，实测可用内存剩 ~700MB 时 llama-server 直接以 `0xc0000005` 崩溃 —— 关掉 Flash Attention 一样崩，与 FA 无关，就是内存不够（对照实验：同机 CPU 路径走 mmap，稳定；75 条查询的整轮校准 22.7 秒）。CPU 与 GPU 用的是同一份权重，向量空间一致，阈值可以沿用；但 `document_chunks.embedding_model` 记的是模型名，换名之后必须 `reindex`。

### 2.1.3 云端 qwen3.7-text-embedding 校准（2026-09-22）

触发原因：云端向量档（百炼网关，原生 1024 维）上线，量纲与本机 bge-m3 不同，必须单独校准 —— 复用 bge-m3 的 0.50 或 mock 的 0.35 都是跨向量空间套用。

黄金集（75 条查询 / 65 个 gold target）阈值曲线：

| minScore | recall@5 | MRR | 拒答正确率 | 可答为空 | 过门禁 |
| --- | --- | --- | --- | --- | --- |
| 0.35 | 1.0000 | 0.9909 | 0.7273 | 0 | - |
| 0.40 | 0.9846 | 0.9818 | 1.0000 | 1 | yes |
| 0.45 | 0.9846 | 0.9818 | 1.0000 | 1 | yes |
| 0.50 | 0.9538 | 0.9455 | 1.0000 | 3 | yes |
| 0.55 | 0.8462 | 0.8182 | 1.0000 | 10 | - |

通过区间是 **[0.40, 0.50]**，但**取区间下沿的 0.45，不是上沿 0.50**：拒答正确率在 0.40 就已饱和到 1.0000，
0.50 相对 0.45 换不来任何拒答能力，代价是多误拒 2 条可答问题（0.45 空 1 条，0.50 空 3 条：
「远程访问公司内网需要先连接什么？」「运行快照写入哪张表？」「标准付款周期是多久？」）。
校准用例的默认打印是「仍满足门禁的最大阈值」，那是个偏保守的启发式；门禁（拒答 >= 0.80）比实际达到的 1.0000 松得多时，
它会把「白白丢召回」的那一档推上来 —— 所以**报告建议值要人工复核**，这一条正是例子（§2.1.2 的 0.55 -> 0.50 同理）。

第二语料复测（`samples/kb/` 8 篇 / 11 片，新 kb `handbook-qwen`，11 条查询在 `minScore=0.05` 下取全量分数）：

- **噪声天花板 0.3709**（问「公司年会在哪里办？」，语料里没有答案）：0.35 这条 mock 量纲的默认值在 qwen 上同样不安全，
  与黄金集同点拒答 0.7273 互相印证。`>= 0.40` 是硬下限。
- **必保命中下限 0.5769**：问「一线城市住宿标准是多少？」时，现行版排第二（0.5769）、**已废止**的 2024 版排第一（0.6379），
  要留住现行版就不能把阈值抬到 0.55 以上。这与 bge-m3 相反（同题正确片只有 0.5232，0.55 会被切掉）——
  所以「0.55 在本机档误拒」的结论**不能**直接搬到云端档。
- 取 **0.45**：距噪声天花板 +0.079，距必保下限 -0.127，是两个语料交集 [0.40, 0.50] 里误拒最少的一档。

两家真实模型的排序病对照（同一套语料、同一批问题，说明哪些是模型问题、哪些是检索范式问题）：

| 问题 | bge-m3（0.50） | qwen3.7-text-embedding |
| --- | --- | --- |
| 忘记打卡怎么办？ | **噪声第一**（06 运维手册 > SSE 断流 0.5293 > 正确片 0.5232） | 正确片第一（0.6178），且只召回 1 条 |
| 一线城市住宿标准？ | 已废止版第一（0.6851 > 现行 0.6095） | 已废止版第一（0.6379 > 现行 0.5769） |
| 报销单最晚什么时候提交？ | 现行版第一，废止版第二（0.6843 / 0.6727） | 现行版第一，废止版第二（0.6042 / 0.5988） |

读法：第一条是**语义能力差异**（qwen 对同义改写更强，噪声压下去了），第二条两家都犯 —— 属纯向量检索的固有缺陷，
靠调阈值治不好（现行/废止两片措辞几乎相同，向量本来就分不开），要根治得上混合检索 + 时效性权重（M4 待办）。

复现（密钥只从环境变量读，报告落 `target/eval/`）：

```powershell
$env:AIFLOW_EVAL_EMBEDDING_API_KEY = 'sk-...'
mvn test -Dtest=RagRealEmbeddingCalibrationTest -DfailIfNoSpecifiedTests=false `
  '-Daiflow.eval.embedding.mode=http' `
  '-Daiflow.eval.embedding.base-url=https://ws-qb2rjzcwpk43bq03.cn-beijing.maas.aliyuncs.com/compatible-mode/v1' `
  '-Daiflow.eval.embedding.model=qwen3.7-text-embedding'
```

启用姿势（5 个环境变量）见 README「云端真实向量档」；换模型后必须 `POST /api/v1/kbs/{kbId}/reindex`，
否则检索会以「向量来源不一致」显式失败。

### 2.2 对话/工作流黄金集

- 每条：输入 + 期望行为（成功 / 降级话术 / 拒绝）+ 可接受答案要点。
- 触发时机：prompt 改版、工作流定义改版、provider 路由策略变更 —— 必须跑回归并记录指标。
- 2026-10-14 第二层切片已验收：细节见 `provider-layer2-plan.md`，结果见 `provider-layer2-validation.md`。三个版本化合成 JSON 集覆盖上下文/记忆/画像、工作流故障与降级、RAG 引用与提示边界；真实装配/执行逻辑配可控端口，默认离线。各组最低 12/10/10 条且契约通过率必须 100%；当前 24/21/18 条全部通过，另有设施 9 条，全量 975 条、0 失败/错误、6 条按开关跳过。未知/漏跑/重复场景失败，报告只保存 case id/统计/资源哈希，不保存正文或环境属性。mock 契约通过率不代表真实模型质量；既有 RAG 检索指标与阈值不变。

## 3. 故障注入清单（M3 起常态化）

| 故障 | 注入方式 | 期望行为 |
| --- | --- | --- |
| 模型超时 | mock 延迟 > 节点超时 | 节点重试 -> 降级话术，不挂死 |
| 429/限流 | 路由层模拟错误 | 退避重试、熔断摘除、矩阵降级 |
| 返回坏 JSON | mock 输出非法结构 | 解析失败计入重试预算，最终兜底 |
| Redis 不可用 | 停 redis 容器 | 锁关闭模式下不受影响；开启时快速失败并提示 |
| DB 连接耗尽 | 压测打满池 | 快速失败 + 明确错误码，不无限等待 |
| 事件断流 | 客户端断线重连 | `Last-Event-ID` 回放，无重复终态 |

## 4. 日志与可观测（M1 已具备）

- traceId 贯穿 HTTP -> 会话任务 -> 节点执行（MDC 传播，`MdcScope`）。
- 运行级：`runId/sessionId/userId` 进 MDC；运行与节点快照落库（回放基础）。
- 结构化日志：`json` profile 下 console 输出 logstash 格式；审计日志（谁调了什么工具、访问了什么数据）与业务日志分离（M3 起）。
- 指标：Actuator + Prometheus（`/actuator/prometheus`）。
- M4-3A 事件/SSE：`aiflow.events.publish.failures`、`aiflow.events.read.failures`、`aiflow.events.readers`、
  `aiflow.events.subscriptions`、`aiflow.sse.connections`、`aiflow.sse.overflow`；失败/overflow 为本实例累计计数，
  readers/subscriptions/connections 为当前数量。只带固定 `mode` 标签，无 runId/sessionId 高基数标签。
  同一口径可通过 `GET /api/v1/runs/events/status` 手工核对；计数不包含事件正文或凭证。

## 5. 测试分层

| 层 | 手段 | 覆盖 |
| --- | --- | --- |
| 单元 | JUnit5 + AssertJ，无 Spring | 图校验、执行引擎、循环防护、串行队列、路由 |
| 集成 | @SpringBootTest + H2 | 上下文装配、API 契约、持久化映射 |
| 端到端 | 控制台 + curl | 建用户/会话/消息 -> SSE 全事件链 |
| 压测 | Gatling + mock provider | 见 1.2 场景矩阵 |


## 6. 人工审批验收记录（2026-09-22）

范围：M5「人工审批节点（超时/升级）」提前交付的第一版（契约 `docs/approval-node-design.md`；通知只做 SSE 事件 + 日志 + 待办接口）。

| 项 | 手段 | 结果 |
| --- | --- | --- |
| 挂起语义（落检查点 / 节点行 WAITING / 不发收尾事件 / 恢复不重放 / 版本变更拒绝恢复 / 步数跨挂起累计） | `ExecutionEngineSuspensionTest`（6 条，无 Spring） | 全绿 |
| 装配期校验（缺审批人 / 配了重试 / 非法 onTimeout / onExhausted=escalate / 超部署上限 / 正确配置放行） | `ApprovalNodeExecutorTest`（7 条） | 全绿 |
| 端到端（挂起 -> 待办 -> 决策 -> 恢复 -> 终态；403 不消耗决策；重复决策 409；取消同时收口；超时三条策略 approve / reject / escalate + 升级到顶走 onExhausted；onReject=fail 判 FAILED） | `ApprovalFlowIntegrationTest`（10 条，H2 + MockMvc，全离线） | 全绿 |
| 真实栈（真实 PG + 真引擎 + V6 迁移，最终构建复跑） | `script/verify-approval-flow.ps1`（run `2c518d7a` / 第二条 `ed36fdee`） | 4 项检查全过：挂起 -> APPROVE -> SUCCEEDED；**挂起期间同会话第二条消息 354.9ms 自己跑到挂起**（不持锁）；取消后待办清空、再决策 409；取消的断点节点行收口为 FAILED + 「审批等待期间被取消」且检查点已消费、run `steps=2`（不把「跑了两步才挂起」记成 0）；已批准那条 start/approve/end 各一行（不重放） |
| 全量回归 | `mvn test` | **388** 用例（387 执行 + 1 默认跳过），0 失败，BUILD SUCCESS（2026-09-22） |

已知边界（不当 bug 藏）：

- `node_runs` 断点行的耗时含等待时间（`ended_at` 在恢复那一刻才写）；真实等待看输出里的 `approvalWaitedMs`。
- 挂起期间会话**不持锁**：同会话下一条消息可以先跑完 —— 有意的取舍（锁住会话等于把人不在的时间也算进会话阻塞）。
- 决策人身份来自调用方自报（`decidedBy`），**不是安全边界**；鉴权落地时在 `ApprovalController` 接真实身份。
- 通知第一版只发 SSE 事件 + 日志 + 待办接口；邮件 / IM 适配器与审批看板未做。

## 7. M4-3A 验收契约（已批准，本轮实现与核心验收完成）

所有真实验收实例强制 mock 对话与 embedding，关闭摘要、长期抽取及真实模型探活；只用合成用户、会话与工作流。
真实 PG/Redis 与纯单测各证明自己的范围，不能用 H2/Mockito 报告替代 Lua、Stream、多实例和持久性证据。

| 验收入口 | 必须核对的事实 | 报告与边界 |
| --- | --- | --- |
| `RunEventBusTest` / `RunStreamServiceTest` / `RunEventsControllerTest` | 并发 seq 顺序、回放注册失败清理、专用发布异常隔离、慢客户端队列有界、断流清理、非法 400/缺口 409 | 纯单元/API，不证明 Redis 持久性 |
| `RedisRunEventBusIntegrationTest`（`-Daiflow.redis.integration=true`） | 真实 Lua 原子 seq、精确 MAXLEN 2000、TTL 与 counter 生命周期、XREAD 广播/续传、发布失败/有限读重试、缺口 | 随机测试 key；不删 Redis key；常规 test 默认跳过 |
| `script/verify-distributed-flow.ps1` | 双实例交替同会话提交，task seq 与执行段不重叠；跨会话重叠；幂等；另一来源审批/取消；恢复不重放、挂起放锁 | `target/m4-3/distributed-flow-verify.json`；可加只读 `skill.list` 工具；两端都开 worker 时不能仅靠请求来源证明不同执行 owner |
| `script/verify-redis-events.ps1` | B 读取 A 提交运行的完整连续事件；转 A 续传不重不漏；非法/负/未来游标 400；终态与 PG 快照一致 | `target/m4-3/redis-events-verify.json`；`-ReplayRunId` 专验外部重启后回放；应用、Redis 普通重启和容器重建分别留报告 |
| `script/verify-dispatch-failures.ps1` | 小 TTL 下 >3 倍 TTL 仍完成；替换合成 session 锁 token 后旧 owner 失败且不推进/解别人的锁；可选 429 无半成品/不占幂等键 | `target/m4-3/dispatch-failures-verify.json`；只操作本轮会话锁，测试 token 为锁所有权标记，报告不回显值 |
| 故障脚本 crash/recovery 两段 | 明确标记的 A 进程死亡；QUEUED 由存活 worker 推进；RUNNING 收 FAILED、不自动重放前置工具/节点 | `-CrashEnginePid` + 唯一 JVM 标记，A 开 worker/B 关；调用者启动 B worker 后 `-RecoveryReport` 验恢复，不能只看到 PID 死亡就宣称恢复正确 |

事件发布故障必须分别覆盖 RUN_STARTED、NODE_STARTED、DELTA 与终态事件：只隔离 `EventPublishException`，
run/node/task 仍按 PG 事实收口；失去租约的异常不得被该入口隔离。订阅读失败有限重试后，已建流发送无运行 seq 的
`STREAM_ERROR` 并关闭，未建流明确 409/503。AOF everysec 只承诺已说明的持久性边界，不承诺主机故障零丢失。

每份验收报告应记录输入配置、实例角色、合成 run/session ID、断言和未覆盖范围；实际结果由本轮执行后回填。
A 的功能正确性报告不替代 C 的负载基线与前后对照，1k SSE/200 并发目标仍不能作为当前生产容量。

本轮 M4-3A 验收结果与限制见 [m4-3a-validation.md](m4-3a-validation.md)。

## 8. M4-3B 画像验收（2026-10-09 已交付）

契约见 [profile-design.md](profile-design.md)，完整结果与复现命令见 [m4-3b-validation.md](m4-3b-validation.md)。
本轮使用真实 PostgreSQL/pgvector、Redis 和引擎；对话与 embedding 强制 mock，关闭所有真实模型端点及探活、云知识库与长期抽取。
短期记忆回归开启 mock 摘要，验证摘要折叠及装配；合成用户、消息、工作流与报告放 `target/m4-3b/`。

| 验收入口 | 核对事实 | 本轮结果与边界 |
| --- | --- | --- |
| `ProfilePreferenceResolverTest` / `ProfileStatisticsTest` | MANUAL/PREFERENCE 白名单、停用、none、显式 model、预算优先级、读取失败关闭；非法/缺失 token 为 unknown | 新增 11 条纯规则测试 |
| `ProfileIntegrationTest` / `ProfileServiceTest` / `ProfileStatisticsPersistenceTest` / `ProfileStatisticsRepositoryTest` | 默认关闭/GET 不建行、版本冲突、首次启用竞争、30 天与启用窗口、时区、审批恢复不重复计数、工具复用、重置保留源记录 | 新增 18 条 API/服务/持久化测试 |
| `LlmNodeProfilePreferenceTest` | chat/vision 使用同一偏好装配，画像与显式 model/预算的优先级，节点 none 禁用 | 新增 6 条节点接线测试 |
| `SessionMemoryServiceTest` | 当前user输入及历史user/assistant回显上下文标签，不误算系统块或历史条数 | 新增 2 条回归，分别遍历三种标签；本切片合计新增 37 条 |
| 全量 `mvn test`（`-Daiflow.redis.integration=true`）与打包 | 全量回归、真实 Redis Lua/Stream 合约和可运行产物 | 606 条、0 失败、0 错误、1 跳过，BUILD SUCCESS；唯一跳过为真实 embedding 校准，package 成功 |
| `script/verify-profile-flow.ps1` | 真实 PG 执行 V10，默认不建行、启用、统计/token/时区对照、偏好/none、400/409、逻辑重置、重新启用、profileIncluded、审计覆盖边界 | 23/23；`target/m4-3b/profile-flow-final.json`，SQL 只读核对，最终jar复跑 |
| `script/verify-memory-flow.ps1` | mock 摘要折叠、窗口与真实装配预览一致 | 20/20；`target/m4-3b/memory-flow-final.json`，最终jar复跑 |
| `script/verify-approval-flow.ps1` | 挂起放锁、决策恢复、不重放、取消与冲突 | 4/4；`target/m4-3b/approval-flow-verify.json` |
| `script/verify-long-memory-flow.ps1` | 手工条目、跨会话召回、真实运行命中计数、装配与合成条目删除 | 21/21；`target/m4-3b/long-memory-flow-verify.json`；未传 `-ExpectExtraction`，仅删除脚本自己创建的合成条目，未删除原数据 |
| 控制台真实浏览器 | 默认关闭、启用、改时区保留起点、白名单/none/显式 model、停用、逻辑重置保留两条手工偏好、切换用户清空旧视图 | 通过；无浏览器 warn/error 日志；截图 `target/m4-3b/profile-ui-reset.jpg` |

停用缺陷已由回归修复：JPA 偏好快照传递实际 `aggregation_enabled`，停用后不再应用画像偏好。
会话装配预览以 system 角色和标签共同识别画像/记忆/摘要块，用户正文同名标签仍是普通消息；修复前已由真实浏览器复现 historyCount=-1。
控制台同时防止旧用户响应和旧版本覆盖当前视图。GET/预览不回写画像状态或记忆 hits；逻辑重置不删除运行、审计或长期记忆。

工具统计依赖持久审计的保留期，丢写与失败计数只代表本实例；已记录对话 token 不含摘要、抽取、embedding，不是完整账单，工作流分布不是语义话题分类。
当前没有 Subject→userId 归属映射，验收不证明真实多用户隔离。真实付费抽取与真实 embedding 校准未重跑，历史真实模型结果不作为本轮证据。
B 的功能正确性验收不替代 C 的负载基线；本轮未实施 C/D。
