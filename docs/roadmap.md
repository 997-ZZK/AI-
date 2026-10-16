# 里程碑与验收

## M1 引擎核心（已完成）

目标：一个能跑、能看、能压的工作流引擎骨架，覆盖「从聊天到干活」的执行底座。

已落地：
- [x] 工程骨架（Spring Boot 3.5 / Java 21 / Maven）、docker-compose（PG 15432、Redis 16379，避开同机其他项目）
- [x] 工作流定义模型 + 校验器：重复 ID、未知节点类型、自环、环检测（Kahn）、入口与可达性
- [x] 执行引擎：拓扑执行、条件边（SpEL）、节点重试、节点超时、步数预算、取消
- [x] 事件总线：单调 seq + 环形缓冲回放；SSE 支持 `Last-Event-ID` 与心跳
- [x] 会话：按会话串行队列（同会话 FIFO / 跨会话并行）、SessionLock 接口 + Redis 实现（可开关）、`clientMsgId` 幂等
- [x] 内置节点 `start / llm.chat / delay / end`；MockChatProvider（可配延迟与分片）+ ProviderRouter
- [x] Flyway V1 六张表；`JpaRunRecorder` 两级快照
- [x] 统一响应/错误码/全局异常；traceId 过滤器 + MDC 贯穿到会话任务
- [x] 默认工作流 `basic-chat` + 网页控制台 `static/console.html`

待补：
- [x] 压测基线：mock 延迟模型 + Gatling 场景脚本，已由 M4-3C 阶段交付；各包的负载归属与失败边界见 `m4-3c-validation.md`。
- [x] CI（2026-10-16）：已获授权并新增 `.github/workflows/ci.yml`，使用 Java 21 + Maven cache，执行纯 mock/H2 的测试与打包，失败上传 Surefire 报告；不启动真实数据库/Redis/MinIO、不访问厂商、不读取凭证。本地首个提交已落在 `feature/ci-pipeline` 并推送到 GitHub，首次 hosted runner 页面状态为 `success`，远端与验证记录见 `docs/ci-validation.md`。

验收口径：
1. `mvn test` 全绿（引擎算法为纯单测）。
2. 控制台完成「建用户 -> 建会话 -> 发消息 -> SSE 收到 DELTA 与终态事件」。
3. 同会话两条消息顺序执行；跨会话并行不互相阻塞。
4. 坏图（环 / 悬空边 / 未知类型）在注册时被拒绝并返回明确问题列表。

## M2 RAG 全链路（当前）

已落地：
- [x] `RetrievalProvider` 抽象：本地与云端知识库同接口；`ScoreNormalizer` 按 source 归一化到 [0,1]（本地 `max(0, cos)`，云端按配置量程线性映射）
- [x] 文档流水线：text / markdown / json 解析 -> 结构感知切片（docId / chunkIndex / 标题路径 / 页码 / contentHash）-> 1024 维 embedding -> 入库（`documents` / `document_chunks` + pgvector 列 + HNSW 索引）
- [x] `EmbeddingProvider`：mock（确定性哈希，离线可复现，供测试与压测）+ OpenAI 兼容 HTTP（云厂商 / 本地 vLLM / Ollama 同一协议）
- [x] 检索后处理纯函数链：阈值过滤 -> 断崖 top-k（minK / maxK / cliffRatio）-> MMR 去冗余 -> 相邻切片合并
- [x] 提示词组装：`[n]` 编号 + 引用要求 + 注入边界声明
- [x] 节点 `retrieval.search` + 示例工作流 `rag-chat`；API：文档上传 / 列表 / 切片 + 检索调试端点；建会话可绑定 `workflowName` 选择工作流
- [x] 黄金集 9 篇 / 66 条（可答 55 + 不可答 11，含域内外两类）：recall@5、MRR、拒答正确率纳入 `mvn test` 回归；mock embedding 校准点实测 0.923 / 0.918 / 0.909，报告含阈值曲线（`target/eval/rag-report.json`）
- [x] 切片器与裁剪链修正：标题行并入下一段正文（首块即带标题路径）、纯重叠尾块丢弃、断崖落在 minK 保护区内按 minK 截断
- [x] RAG API 集成测试（H2 + memory 向量库 + mock provider）：入库 / 重复检测 / 检索端点 / 上传与类型拒绝 / `rag-chat` 端到端出引用
- [x] 控制台升级：会话选工作流、文档上传与文本入库、检索调试表格、终态输出展示引用
- [x] 真实链路实测（`docker compose`）：PostgreSQL 16.15 + pgvector 0.8.6 上 Flyway V1/V2 应用成功，入库 -> pgvector 检索 -> 控制台 SSE 问答全链路核对
- [x] 引用标签去重（H1 进标题栈导致的「标题 > 标题」）与融合去重同分容差（`1e-9`，防跨来源浮点噪声让来源归属漂移）
- [x] 真实 embedding 阈值重校准脚手架：`RagRealEmbeddingCalibrationTest`（默认跳过）、评测骨架 `RagEvalHarness`、报告含阈值曲线与建议阈值
- [x] V3 迁移：`document_chunks.embedding_model` + 按模型隔离检索 + `POST /api/v1/kbs/{kbId}/reindex` 一键重建；索引与当前 provider 不一致时显式失败（实测 503 带重建命令 -> reindex -> 恢复）
- [x] 日志分流：静态资源 404 不再被兜底 handler 吃成 500；SSE 客户端断流（心跳 flush 失败 / 容器异步错误回调 / `complete()` 自身抛错）全部降为 debug

待补（并入 M3）：
- [x] pgvector ANN 查询路径（2026-09-22）：本地检索先走 `order by embedding <=> cast(? as vector) limit N`（HNSW 索引 `idx_chunks_embedding`），回表只取命中切片；`ann.enabled=false` / 存储不支持 / **ANN 查空**三种情况回退精确余弦（过滤型 HNSW 的后过滤可能扫空，空结果不能当「没命中」）。产出的分数与精确路径同口径，后处理链一行未改。实现里踩到并修掉一个真缺陷：用 `show hnsw.ef_search` 探活会误判成「不支持」（`hnsw.*` 是扩展 .so 里的 GUC，本会话没用过索引前 `show` 报 unrecognized），改为按扩展版本判定（ef_search >= 0.5 / iterative_scan >= 0.8），并用越界值 1e9 实测确认 `set local` 真发到了 PG 且失败时自动无参数重试、结果不变。实测：真实语料 ANN 与精确逐条一致；5000 条切片基准 p50 878ms -> 30.5ms（≈29×），召回 8/8。脚本 `script/verify-ann-retrieval.ps1` / `script/bench-ann-scale.ps1` / 造数 `script/sql/bench-ann-needles.sql`
- [x] MinIO 对象存储与上传原件留存（2026-09-22）：新增 `engine/storage`（`ObjectStorage` 接口 + MinIO 实现 + 未开启实现 + 装配）。选择与取舍：① **切片进 PG、原件进对象存储**，库里只记 `object_key` / `object_bytes`（V5 迁移）；② **留存失败不阻断入库** —— 写不进去只 WARN + `object_key` 留空，文档照旧 INDEXED（对象存储是可选能力，做成硬依赖等于"它抖一下用户连知识库都用不了"）；③ 下载接口把"没有原件"分成三种口径：能力没开 503 / 这次没留住 404 / 对象存储里对象不见了 404，排障动作不同不能都回"没有"；④ 键 `kb/{kbId}/documents/{documentId}/{fileName}`（文件名净化），按前缀即可清理整库；⑤ 凭证只从环境变量来，开了但缺凭证 = 按未开启处理 + WARN。依赖用 MinIO 官方 SDK（8.5.17，okhttp 一条线；S3 兼容协议对 MinIO/Ceph/OSS 通用，换厂商只改 endpoint）。镜像走 quay（docker hub 镜像站对 minio 命名空间 403，已写进 compose 注释）。接口级实测（`DocumentOriginalStorageIntegrationTest`，内存桩）：上传 -> 原件落库 -> 下载与上传**逐字节相同**、Content-Type/Length 一致、"写不进去"时入库仍成功且 objectKey 为 null。真实端到端（2026-09-22，真实 MinIO + 真实 PG）：上传 PDF -> objectKey 回显 -> 下载 SHA256 与上传相同；`mc ls` 在 MinIO 侧独立看到同一对象；`docker stop` 后上传仍 INDEXED 但 objectKey=null、日志有 WARN、取原件 404；`docker start` 后再传一份恢复为 objectKey 非空且 SHA256 相同 —— 瞬态失败，不是永久退化。
- [x] 云端真实端点扩充与真实向量档（2026-09-22）：① 百炼网关旧密钥三条路径全 401，换新密钥后 `qwen3.8-flash` 跑通对话 run `5b60b9a4`（`provider=qwen`）；② 新增 DeepSeek 端点，run `1940e191` 验证**同档位第二家自动接管**（关掉 qwen 后 `provider=deepseek`）；③ 两家都是推理模型，统一用 `extra-body: {"thinking":{"type":"disabled"}}` 关思考（不关时 DeepSeek 的 16 个 token 全被 reasoning 吃掉、content 为空串）；④ **百炼 `qwen3.7-text-embedding` 原生 1024 维**，云真实向量档无需装 Ollama 即可启用，启动日志新增生效链条清单（哪台主机伺候哪个模型 —— 同维不同模型的向量混入不会报错、只会静默算错相似度）；⑤ 合成语料（不含用户数据）验证语义改写召回 4/4 命中首位（raw 0.54~0.81，mock 量纲下同批查询只有 0.04~0.20），ANN 与精确在真实向量下逐条一致。脚本 `script/verify-realvec-retrieval.ps1`
- [x] 真实向量阈值重校准（qwen3.7-text-embedding，2026-09-22，用户授权后跑）：黄金集（75 条查询 / 65 gold target）通过区间 **[0.40, 0.50]**，
  取 **0.45** —— 拒答正确率在 0.40 就饱和到 1.0000，0.50 换不来任何拒答能力却多误拒 2 条可答问题（空 1 -> 空 3）。
  校准用例默认打印「仍满足门禁的最大阈值」是偏保守的启发式（门禁 0.80 比实测 1.0000 松太多），这次又被人工复核压回区间下沿 ——
  与 §2.1.2 的 0.55 -> 0.50 是同一类错误模式。第二语料（`samples/kb` 灌进新 kb `handbook-qwen`）复测给出硬边界：
  **噪声天花板 0.3709**（0.35 在 qwen 上同样不安全，与黄金集同点拒答 0.7273 印证）、**必保命中下限 0.5769**（现行版排第二、
  已废止版排第一）—— 注意与 bge-m3 相反（同题正确片 0.5232，0.55 会被切），跨模型搬结论会被打脸。
  三家量纲现在是 mock 0.35 / bge-m3 0.50 / qwen3.7-text-embedding 0.45，配置注释与 `docs/quality-plan.md` §2.1.1 + §2.1.3 已同步

- [ ] 云端知识库真实厂商联调（M2 只交付 HTTP 适配器 + mock 契约测试）。已备探形脚本 `script/probe-cloud-kb.ps1`：真实厂商的请求/响应形状与分数量程一次抓全（本地桩验证通过），映射照抄而非猜测；卡在厂商参数（base-url / 检索路径 / 鉴权形态 / 协议文档），拿到即写映射并联调。


验收：
1. `mvn test` 全绿（M2 交付时 137 个用例；M3 收尾后 194 个 = 193 执行 + 1 个默认跳过的真实 provider 校准用例），含黄金集回归（门槛写死在测试里，指标下降即失败）、`RagApiIntegrationTest` 与 `VisionFlowIntegrationTest` 端到端。
2. 同一批切片在「本地检索」与「云端知识库（mock）」两条路径下，统一阈值与裁剪行为一致（契约测试）。
3. 运行输出可溯源：每个 `[n]` 都能映射回 docId + chunkIndex + 分数 + 来源。

## M3 分级路由与多模态（进行中）

已落地：
- [x] `CapabilityRegistry` + 降级矩阵 L0-L3：`GET /api/v1/capabilities` 给出当前档位、每个候选档位的可用性与原因、以及用户可见的降级提示。实测两轮：① 本机装了 Ollama 但没拉对话模型时 `level=L2`、`chat.activeProvider=cloud`，`local` 档原因是「端点在线但模型列表中不含 qwen2.5:1.5b」；② 模型拉下来后探活转 `up`，但真实调用仍以 `0xc0000005`（Windows CUDA 路径内存不足）失败并自动降级到 cloud，快照里留下 `degradedFrom: [local]` —— 探活只核对「端点 + 模型清单」，**绿灯不等于能生成**，所以对话档的验收标准是「真实一发一答的 provider 快照」，不是探活状态
- [x] 分级路由：`order` 定义档位顺序（默认 `[cloud, local, mock]`：质量优先、本地兜底，依据见下文本地对话档实测），调用失败自动退下一档；「已向用户推送过增量」的调用不换档（否则两段输出会拼接）；用户取消不换档
- [x] HTTP provider 真实化：`OpenAiCompatibleChatProvider`（SSE 增量解析、错误响应转可读异常、model 可被请求覆盖），本地 Ollama 与云端网关同一份契约，端点由配置声明（有变化时保存后重启即可）
- [x] 端点点健康与熔断（自研约 120 行）：探活 `GET {base-url}/models` 每 15s 一次 + 真实调用失败计数，连续失败达阈值打开熔断，冷却结束自动放行；探活除了看 2xx 还核对 `/models` 清单里有没有配置的模型名（装了 Ollama 但没拉模型 = 服务活着而请求必 404，这种「假可用」比报错更糟）；探活判定挂掉的档位直接跳过，但会在 run 快照里留名
- [x] 降级留痕：`llm.chat` 节点输出带 `provider` / `skippedTiers`（探活判死直接跳过）/ `degradedFrom`（尝试过但失败），实测快照 `{"provider": "mock", "skippedTiers": ["local"]}`
- [x] 向量化分级链：`embedding.tiers` 多档兜底（本地兜底 / 云端加速），启动期强制校验各档 model 一致 —— 不同模型的向量不在同一空间，允许互为兜底就是允许静默算错
- [x] 离线可用性验证：未装任何模型时，RAG 入库 -> pgvector 检索 -> mock 对话全链路可用；降级原因对用户可见
- [x] 多模态节点 `llm.vision` + 附件上传 `POST /api/v1/attachments`：文件头嗅探定类型、UUID 落盘 + sidecar 元数据、日志与快照不留 base64；能力门槛 chat + vision，缺失时给可操作提示；示例工作流 `vision-chat`，控制台可直接传图提问
- [x] 能力矩阵新增 `vision` 通道（`GET /api/v1/capabilities`）：没有真实多模态档位时由 mock 承接，并在 notice 里明说「只回图片摘要」
- [x] 云端档 runbook：`script/run-cloud-embedding-calibration.ps1`（密钥只走环境变量、报告落 `target/eval`）+ README「云端档接入」；真实阈值重校准与云端联调均已跑通（见下方「真实模型联调」一条）
- [x] 工具调用 `tool.call`：装配期校验（url 为空 / host 不在白名单 / 方法不支持直接拒绝）+ SSRF 四道检查（协议与 userinfo、端口白名单、host 白名单按域名边界匹配、解析地址不得为回环/内网/链路本地/`169.254.169.254`）+ 运行级护栏（次数上限、同指纹重复超阈值判循环、幂等缓存、`3xx` 拒绝跟随重定向、响应体截断）；`enabled=false` 时**在装饰器层直接拒绝**，不是「配了但不生效」。默认全关：打开等于允许引擎主动出网，必须是显式选择
- [x] MCP 工具接入（2026-09-20）：自研 stdio 客户端（newline-delimited JSON-RPC 2.0：`initialize` -> `tools/list` -> `tools/call`），每服务端一条常驻进程、串行调用、stderr 单独抽干留尾、超时即杀进程；配置导入支持从 Codex 的 `config.toml`（`[mcp_servers.*]`）与 Claude 的 `mcpServers` 取 stdio 形态（写 `url` 的 HTTP/SSE 形态明确跳过并记日志）；单服务端起不来只记 `failures`，不炸 Spring 上下文。实测：导入 `~/.codex/config.toml` 得到 `codex-node_repl` 的 4 个工具；工作流 `m3-mcp-demo` 调 `mcp.codex-node_repl.js` 拿回 `"aiflow-m3: 42"`（快照 `kind=mcp`、`serverId`/`mcpTool`、`durationMs`）
- [x] 本机技能库接入（2026-09-20）：扫 `SKILL.md`（frontmatter 取 name / description）暴露两个工具 `skill.list`/`skill.read`，而不是「一技能一工具」—— 技能会增长，把它们全塞进工具清单等于让编排者每次读一遍目录；新增技能不需要改配置。实测：6 个根目录扫出 25 个技能（`.agents/skills` 2、`.claude/asu-skills/skills` 9、`.codex/skills` 6、`.codex/plugins/cache` 8），工作流 `m3-skill-demo` 三节点 SUCCEEDED（list 命中 `project-guide`、read 取回 `great-resume` 正文）
- [x] 工具清单出口 `GET /api/v1/tools`（白名单 + 每服务端工具数 + MCP 失败原因 + 技能根目录与数量）与 `POST /api/v1/tools/reload`（重新发现外部来源，不重启）；三个来源共用同一套运行级护栏（次数 / 循环指纹 / 幂等），因为「一次运行里工具被怎么用」与工具来源无关
- [x] 工具粒度授权与调用审计（2026-09-20）：`aiflow.tools.policy` 按规则给"谁能调哪个工具"下结论（首个命中生效 / deny 压 allow / 空白名单不等于所有人），拒绝发生在**调用之前**并把原因带进节点错误；审计记 谁 + 什么工具 + 结果（SUCCESS / REPLAYED / FAILED / DENIED）+ 耗时 + 参数指纹（不记原文），同时打 `aiflow.audit` 日志。三个查询口：`/api/v1/tools/policy`、`/policy/evaluate`（试算不产生调用）、`/audit`。实测：同一工作流，名单内用户 SUCCEEDED 且桩服务收到 1 次请求，名单外用户 FAILED（错误含自定义原因）且桩服务计数不变；两条记录都能在审计接口查到
- [x] PDF / 图片入库（2026-09-20）：PDF 走 PDFBox 抽文本层，**只有抽不出字的页才渲染成图片送 OCR**（多模态档位，逐字转写提示词）；图片直接走 OCR；切片按页归属，引用带页码。默认关（按页计费），`AIFLOW_OCR_ENABLED=true` 打开。实测：有文本层的 PDF 0 次模型调用、扫描件与 PNG 经云端 `deepseek-flash` 逐字识别出中文，检索命中并给出 `xxx p.1` 引用；`.docx/.zip` 等二进制明确 400（不再按 UTF-8 读成乱码）；OCR 页数超上限直接失败，不静默截断
- [x] 文档入库错误边界（2026-10-15）：PDFBox/图片解码、任意 OCR provider、Multipart 读取和 MinIO/S3 异常统一为固定分类，不回显原始正文、URL、凭证或异常链；对象存储下载区分未启用、对象不存在和存储故障，`stat -> open` 的对象消失返回 404、存储故障返回 503。正常解析、OCR、切片与原件旁路语义保持不变，验收见 `docs/document-ingest-error-validation.md`。
- [x] 对象存储 timeout 接线（2026-10-15）：按 `docs/storage-timeout-design.md` 将已有配置应用于 MinIO HTTP 连接、读写和单次 call 时限，覆盖 `open()` 返回后的流式下载；多个 SDK 请求各自计时，不宣传为整个对象操作 deadline。新增 19 条本地环回协议桩测试，定向 78 条、全量 1108 条（0 失败/错误、6 跳过）及离线打包通过，见 `docs/storage-timeout-validation.md`。
- [x] 内容安全审核（输入侧 + 输出侧同一套规则）：长度（超长直接拒，不静默截断）、敏感词（词表可外置到文件）、违规模式（提示词注入/越狱句式）；只回类别不回命中词，命中的具体项写 DEBUG 日志。输入侧拦在入库与排队之前（400 + 类别，模型的调用次数一次不涨），输出侧换成预设话术 + run 标记 `DEGRADED` + 输出带 `blocked` / `blockCategory`；输出侧另有一层流式增量审核（见下）
- [x] 每用户 token 额度：自然日口径（时区可配，TTL 到次日零点，不依赖定时任务）、软阈值优先便宜档位（`provider.cheap-tiers`，降级不中断）、硬上限给预设话术（`DEGRADED` 而非 500）、`GET /api/v1/budget/users/{userId}` 可查用量与重置时刻；计数支持 memory（单实例默认）/ redis（多实例共享，Redis 不可用时退化为进程内计数并带节流告警，不阻断对话）
- [x] 本地对话档实测（2026-09-20）：`ollama create qwen2.5-cpu`（同一份权重 + `PARAMETER num_gpu 0`）绕开 CUDA 路径崩溃后，`basic-chat` 一发一答 `provider=local`、无 `degradedFrom`（纯 CPU 推理 3~20s）。代价也要记：1.5B 做「从资料里抽答案」不可靠 —— 同一个 `handbook-rag` 提问检索到的两条切片都是对的，local 却答「资料中没有」，cloud 能引用作答；2026-09-20 据此把默认 `order` 翻成 `[cloud, local, mock]`（离线优先可用 `AIFLOW_PROVIDER_ORDER=local,cloud,mock` 换回），切法与取舍写在 README「本地对话档」
- [x] 额度接线的顺序有意为之：判定在调用前、扣减在返回后、内容审核在扣减之后 —— 违规内容不改变「这些 token 已经花掉了」

待补：
- [x] MCP 的 Streamable HTTP 形态与远程鉴权（2026-09-20）：`url` 形态走单端点 POST（响应可为 SSE 流），会话 id 回带 + 404 重握手一次；凭证只从环境变量来（`auth-token-env` 或 headers 里的 `${VAR}`），并且与 HTTP 工具共用同一套出网白名单。抽象出 `McpClient` 让两种传输共用装配与调用逻辑；配置导入同步支持 Codex 的 `url` / `bearer_token_env_var` / `http_headers` 与 Claude 的 `type=http`
- [x] 老式 SSE 双端点的 MCP 传输（2026-09-21）：`GET` 建常驻流等 `event: endpoint` 拿到投递地址 -> `POST` 投递（期望 202）-> 响应从长连接回来按 JSON-RPC id 配对。三个必须做对的地方：① 端点是**服务端下发的不可信输入**，相对路径按建流地址 resolve 后要再过一遍 `SsrfGuard`（测试里用指向 `169.254.169.254` 的端点验证拦截）；② 流断 = 通道断，在途请求立刻失败，下一条请求重新建流 + 重新握手 + **只重试一次**；③ 读线程随 close 自然退出。`McpTransport` 三形态（`stdio` / `http` / `sse`），`auto` 按 command/url 推断、非法值配置期报错；Claude 的 `type: "sse"` 不再跳过而是按 SSE 导入。11 个用例（`McpSseJsonRpcClientTest`，夹具 `FakeMcpSseServerMain` 混入心跳注释与无关事件，逼出真实配对逻辑）
- [x] MCP 凭证的 OAuth 刷新（2026-09-21）：`oauth` 配置块（token-url / client-id-env / client-secret-env / refresh-token-env / scope / extra-params / early-refresh-seconds），有 `refresh-token-env` 就走 refresh_token 授权、否则 client_credentials；与 `auth-token-env` **二选一，配置期报错**。硬点：① **单飞**（锁内二次检查，4 并发只发一次刷新）；② 令牌端点也过 `SsrfGuard`；③ `describe()` 只回「还剩多少秒过期」，令牌不进日志 / 快照，401 提示只点环境变量名。抽象出包私有的 `McpAuth` 把静态 / 动态两种取凭证方式收口，HTTP 与 SSE 客户端共用。8 个用例（`McpOAuthTokenProviderTest`，夹具 `FakeOAuthServerMain` 每次下发不同令牌 + 可设响应延迟）
- [x] MCP 工具热更新的版本一致性（2026-09-21）：`reload()` 改成**先装新、再等旧退场**（`aiflow.tools.mcp.drain-timeout-ms` 默认 5000），在途调用不被掐；等超时改为异步强关 + WARN（同步关会撞客户端的 `synchronized` 锁、把 reload 一起拖住 —— 这个 bug 是「drain 超时」用例先发现的）。每次装配记 `version`（transport | id | 工具指纹 sha256 前 12 位）与 `generation`，`McpToolDescriptor` 带 `fingerprint`；调用前 `verifyTool` 校验「工具还在不在、定义变没变」，变了就明确失败并提示重新发现，而不是把旧参数发往已变的接口。快照新增 `version` / `generation` / `loadedAt` / `auth` / `inFlight`。9 个用例（`McpServerManagerHotReloadTest` 8 + `ToolRegistryTest` 1）。
端到端联调时抓到一个真 bug 并修掉：`reload` 只更新了「发现结果」，没把结果重装进 `ToolRegistry` 的白名单快照 ——
「服务端已注册 7 个工具、执行器却报未注册」就是这么来的。现在注册表用 volatile 不可变快照 + `refreshExternal()` 整体替换，
读侧（run 线程）无锁且永远看到完整快照；HTTP 白名单仍按「改配置走重启」不动。实测：慢调用（5s）跑到 1.5s 时 reload，
reload 等 3584ms 让该调用跑完才关旧连接，run 仍 SUCCEEDED
- [x] 审计持久化（2026-09-22）：新增表 `tool_audit_events`（V4 迁移）+ `ToolAuditPersistence`（单线程、64 条一批、`BlockingQueue`、`@PreDestroy` 收尾排空）。异步是刻意的：工具调用路径上只多一次入队，写库不在调用线程里。降级语义全部可观测 —— 队列满丢最老的（`droppedWrites`）、写失败只计数不重试（`failedWriteBatches`）、查不到库就回退内存缓冲并把来源写进 `store`（database / memory）。保留期默认 30 天，清理挂在写入线程上（`retention-days` / `purge-interval-minutes`），不引入定时调度器。查询用 `Specification` 拼非空条件而不是 `where (:p is null or ...)` —— 后者在 PostgreSQL 上会因 `? is null` 推断不出参数类型而报错，H2 却不报，属于典型的「测试绿、生产红」陷阱（已写进仓储注释）。实测（真实 PG + Flyway V4）：两条审计（SUCCESS / DENIED）落库，重启引擎后 `bufferSize=0` 而 `storeTotal=2`，记录原样可查、可按 userId / outcome 过滤。顺带修掉一条引擎语义：**策略拒绝不重试**（拒绝是确定性的，重试只会多等一轮退避、还让审计多出一行「被拒」；原来 default-max-attempts=2 会记两条 DENIED）。
- [x] 流式增量内容审核（2026-09-20）：`StreamingSafetyGuard` 用**保留窗口**（`aiflow.safety.streaming.hold-back-chars`，默认 32）把最近 N 个字符扣在缓冲里，下一批片段到达时连同窗口一起过 `scanRules`（词表 + 注入模式，不含长度规则 —— 长度只在完整回复上判），通过后才放行窗口之前的部分。窗口重叠保证：任何长度 ≤ 窗口的命中串只要有一个字符落在本片段，就必然完整出现在本次检查范围内且整个候选段都不放行。
  实测（真实 `deepseek-flash`，词表含「杭州」）：① 窗口用默认 32 时命中词的那次 run `606cf721` 是 **0 条 DELTA** + `DEGRADED` + 预设话术，日志留「内容审核命中关键词（增量）: 杭州」；② 窗口 6 的那次 run `2ed79773` DELTA 仅 1 条、文本为「杭」，推送内容不含「杭州」；③ 不命中词表的 run `7ce1bb13` 为 `SUCCEEDED`，**72 条 DELTA 拼接与最终 reply 逐字相同**（133 字）—— 证明保留窗口的收尾补推没丢字也没重字。构造点三处（chat / vision 节点）共用同一层；全量测试 296 用例通过。这一条里的「窗口」是当时的配置值，窗口生效值的**自动抬高**规则见下一条（2026-09-21 补）。
- [x] 长敏感词的窗口兜底策略（2026-09-21）：不搞流式旁路扫描，改为**按最长敏感词自动抬高保留窗口** —— `hold-back-chars` 降级为下限，`auto-window=true`（默认）时生效窗口 = max(配置值, 最长敏感词长度)，`max-auto-window`（默认 200）挡住病态词条（有人往词表里粘了一整段话）；「手动关掉自动抬高」与「词长超上限」两种情形都在装配期打 WARN 明说哪些长度拦不住，不静默。`StreamingSafetyGuard.window()` 暴露生效窗口供诊断与测试。用例 +4（`StreamingSafetyGuardTest` 8 → 12）：抬高后 40 字长词不落 Sink、`auto-window=false` 时窗口 8 装不下 40 字的词（整词会一路推给用户 —— 这正是「长词只能靠最终整段审核兜底」的反例，被测试钉住）、上限截断的两种取值、窗口不小于配置值与词长。测试还逼出一条此前没写清的真实语义：**承诺的对象是整词，不是前缀**（放行按冲刷边界走、不按词边界），javadoc 与 README 已按这个口径改写。
- [x] 真实厂商端点接入（2026-09-22）：火山方舟 `doubao-seed-2-1-pro-260628` 实测打通 chat 与 vision（对话 run
  `provider=ark` / 184 tokens / 端到端 9s；视觉 run `provider=ark` / `imageCount=1`，用自合成的七段数码管图片答出「42」）；
  阿里云百炼专属网关 `qwen3.7-plus` 首测 401（密钥待换），端点配置已就位，换密钥后一个环境变量即可启用。
  接入过程中修掉三个真缺陷：① `order` 说是档位顺序、代码却按端点 id 匹配 —— 新端点会掉到 mock 后面永远轮不到；
  ② `Map.copyOf` 破坏端点声明顺序，同档位「主备」变随机；③ 全局 1.5s 探活超时扛不住方舟几百 KB 的 `/models`，
  档位反复 up/down（实测因此把一次视觉请求误降级到 mock）。为此新增端点级 `probe-timeout-ms` 与厂商私有字段
  `extra-body`（推理链开关：同一问题开思考 >100s、关掉 5s），新增用例 8 个（`ProviderRouterTierOrderTest` 4 +
  `EndpointExtraBodyTest` 4）。
- [x] 真实模型联调与阈值重校准（2026-09-20 实测）：本机 Ollama 0.34.2 + `bge-m3`（1024 维）跑通入库 -> pgvector 检索 -> 对话；真实阈值校准为 **0.50**（mock 量纲是 0.35；黄金集扫出的最大达标点是 0.55，但用 `samples/kb/` 的第二个语料复测发现 0.55 会误拒可答问题，故下调），曲线、两个语料与理由记在 `docs/quality-plan.md` §2.1.1-2.1.2；云端 chat + vision 用 DeepSeek `deepseek-flash` 实测可用（含真实用量扣减与降级留痕）。为了让这件事可复现，顺带改了四处：① 阈值改挂在 `aiflow.retrieval.embedding.min-score`（阈值是**向量分布**的属性，跟着向量模型走），`mode=http` 却没配阈值时启动打 WARN；② `rag-chat` 工作流不再硬编码 `minScore`，改为继承部署阈值；③ 控制台的 minScore 留空即不传，不再用前端默认值悄悄覆盖校准值；④ demo 工作流改按「名字 + 版本」判重注册（原来只要有同名就跳过，导致 `workflows/*.json` 改了什么都不会生效）

- [x] 人工审批节点（2026-09-22，**M5 提前交付**）：`approval` 节点落待办后**挂起运行**（检查点入库、节点行 WAITING、立刻释放会话锁与 worker），
  人工决策或超时收口后从断点恢复；接口 `GET /api/v1/approvals`、`POST /api/v1/approvals/{id}/decision`、`GET /api/v1/runs/{runId}/approvals`、
  `POST /api/v1/runs/{runId}/cancel?actor=`（挂起中的取消会同时收口待办）。三处并发（人工决策 / 超时扫描 / 取消）都用条件更新决出单赢家 ——
  重复决策 409、名单外决策 403 且**不消耗**这次审批。实测（真实 PG + 真引擎，脚本 `script/verify-approval-flow.ps1`）：
  挂起 -> APPROVE -> SUCCEEDED 且 `approvalStatus=APPROVED`；**挂起期间同会话第二条消息 354.9ms 自己跑到挂起**（不持锁的证据 ——
  如果恢复逻辑把会话锁扣住了，第二条会永远停在 PENDING）；取消第二条后待办自动消失、再决策 409。H2 集成用例 6 条
  （`ApprovalFlowIntegrationTest`）+ 挂起/恢复引擎用例 6 条 + 审批节点装配用例 7 条。契约与不做清单见 `docs/approval-node-design.md`。
  **不含**：审批看板、鉴权（`decidedBy` 由调用方自报，不是安全边界）；邮件/IM 通知不做（要真实口令与第三方 SDK），
  改为交付 **log + webhook 通知适配器**（见下条）

与计划的偏差（记录理由，避免以后当成遗漏）：
- 熔断没有引入 Resilience4j。当前需要的是「档位级：探活 + 连续失败 + 冷却」这一件事，自研实现约 120 行、语义贴着档位模型；Resilience4j 的滑窗统计、限流、隔舱目前用不上。等出现「同一档位需要更细的退避策略」的实测需求再接，不为一个注解引入一套配置模型。

验收：断网/摘除云端 provider 后系统按矩阵降级且用户可见明确提示；上传图片可被解析进工作流（`vision-chat` 已跑通，mock 与真实档位共用一条链路）；工具调用在 `enabled=false` 时全拒、打开后只放行白名单内的 host 且内网地址被拦；违规输入不产生模型调用、违规输出被替换成预设话术；额度走到软阈值后换便宜档位、走到硬上限后不再产生任何模型调用。`mvn test` 全量 **350** 个用例（349 执行 + 1 跳过），0 失败，BUILD SUCCESS（2026-09-22 复跑确认；其中长敏感词窗口兜底 +4、审计落库 +10、原件留存 +3、真实厂商端点接入 +9（档位排序 4 + extra-body 解析 4 + provider 请求体 1））。本轮四件事直接相关的用例：PDF / 图片 OCR 入库 14（`DocumentExtractorTest` 9 + `DocumentOcrIntegrationTest` 5）、工具粒度授权与审计 14（`ToolAuthorizerTest` 8 + `ToolAuditServiceTest` 5 + `ToolAuthorizationIntegrationTest` 1），本轮再 +10（`ToolAuditPersistenceTest` 7 + `ToolAuditServiceTest` +2 + `ExecutionEngineTest` +1）、MCP 的 HTTP 传输 11 + 环境变量占位 7、流式增量审核 14（`StreamingSafetyGuardTest` 12 + `ContentSafetyTest` 新增 2）；相关存量：内容安全规则层 13、授权外的工具调用 47、额度 17、MCP 服务端管理 15、配置导入 6、技能库 8。本轮 MCP 一簇新增 28 个用例（SSE 客户端 11 + OAuth 8 + 热更新 8 + 注册表重装 1）。仍未完成：云端知识库真实厂商联调。
人工审批交付后复跑全量：**388** 个用例（387 执行 + 1 跳过），0 失败，BUILD SUCCESS（2026-09-22；新增 23 = 挂起/恢复引擎 6 + 审批节点装配 7 + 端到端集成 10，覆盖超时三条策略与升级到顶）。

- [x] 审批通知适配器（2026-09-22）：`log`（默认开）+ `webhook`（HTTP POST JSON + 可选 HMAC 签名）两个渠道，
  单线程 + 有界队列异步投递（满则丢最老并计数）、`(approvalId, event, level)` 去重、**只在事务提交后**入队、
  失败只计数不回滚审批；webhook 出网复用工具的 SSRF 护栏（协议 / 端口 / host 白名单 / 内网地址），
  过不了护栏时渠道标记未就绪并在 `GET /api/v1/approvals/notify` 里写明原因。事件 = 状态机的一次转移：
  `REQUESTED` / `DECIDED` / `EXPIRED`（升级，`level` 记目标层）/ `CANCELED`，不单独发 `ESCALATED`。
  实测：全量 **404** 个用例（403 执行 + 1 跳过），0 失败，BUILD SUCCESS；新增 16 = 分发器语义 6 + webhook 渠道 7 +
  端到端 3（REQUESTED+DECIDED、取消、升级）。契约见 `docs/approval-node-design.md` §12。
  **踩到的坑（写给以后）**：测试用的 H2 是固定库名 `jdbc:h2:mem:aiflow`，同一 JVM 里**所有** Spring 上下文共用它，
  别的上下文的 `ApprovalTimeoutScheduler` 也会扫到同一张审批单 —— 谁的条件更新先赢谁发通知，而那个上下文可能没配
  webhook。依赖后台扫描的用例因此会看运气（单独跑绿、全量红）。升级用例改为在上下文内显式调一轮
  `ApprovalService.expireDue(未来时刻)`，把 waitSeconds 设长让别的上下文按真实时间扫不到。

- [x] 审批看板 + 客户端错误口径修正（2026-09-22）：`console.html` 新增第 4 节「人工审批」——待办列表（到期倒计时）、
  批准 / 拒绝（`decidedBy` 手输；**故意不放 JWT 与假身份** —— 没有鉴权时，任何「像模像样的登录框」都会被误当成安全边界。
  P3-3 之后这里改为顶部「令牌」输入框 + `/whoami` 自检，见下面那条）、
  单条审批的事件流、按 `runId` 查审批链、通知渠道与队列计数。SSE 订阅补 `NODE_SUSPENDED` / `RUN_WAITING` / `APPROVAL_*`，
  并修掉一个真缺陷：原来「`RUN_` 开头且不是 `RUN_STARTED` 就关连接」**把挂起当终局** —— `RUN_WAITING` 一到就掐断 SSE。
  顺手修第二条真缺陷：**调用方写错的请求返回 500** —— Spring MVC 抛的客户端异常（报文读不出来 / 方法不对 / Content-Type
  不对 / 路径变量类型不对）原本全落进兜底 handler，变成「500 + 一条 ERROR 栈」，调用方会把「请求写错了」当服务端故障重试。
  修法：Spring 6 起这一族大多实现 `ErrorResponse`（自带状态码），但 **`@ExceptionHandler` 只吃 `Throwable` 子类、写不了接口**
  （第一版这么写直接编译失败，是坑），且实现它的类没有共同父类（Servlet 异常一脉 / NestedRuntimeException 一脉），
  所以改为在兜底 handler 里 `instanceof` 分流；再用 `javap` 核实出两个**没实现 `ErrorResponse`** 的漏网者
  （`HttpMessageNotReadableException`、`MethodArgumentTypeMismatchException` —— 它们的 400 只写在 Spring 自己的
  `ResponseEntityExceptionHandler` 里）单列 `legacyClientStatus`。另两条口径：**响应与日志都不回显报文内容**（Jackson 原始
  message 带请求体前几十个字符，可能是用户提示词或备注），HTTP 状态用 Spring 给的原始值（406/409 等枚举里没有的状态不被压成 400）。
  验证：全量 **409** 个用例（408 执行 + 1 跳过），0 失败，BUILD SUCCESS；新增 5 条（`ErrorResponseIntegrationTest`：非法 JSON
  400 且断言 message 不含载荷、空体 400、`DELETE /api/v1/approvals` 405、`text/plain` 415、路径变量 `not-a-uuid` 400）。
  真实栈（新 jar + 8081 + 桩 webhook 接收方）：`script/verify-approval-flow.ps1` PASS；五条 curl 与用例结论一致
  （400 / 400 / 405 / 415 / 400）；webhook 4 条事件实收、签名 valid、`failed=0`、队列 `0/512`。
  控制台在真实浏览器里跑完：待办 1 条 -> 批准 -> 待办 0 + 链上 `APPROVED` + 运行 `RUN_SUCCEEDED`；
  再跑一轮 -> 「事件」按钮打出 `REQUESTED` 事件流 -> 拒绝 -> 链上 `REJECTED` + 运行终态。

- [x] 鉴权最小切片（2026-09-22，契约见 `docs/auth-design.md`）：静态 Bearer 令牌 -> 主体（`Subject`），
  `@RequireIdentity` 只标两个「身份即边界」的接口（审批决策 / 取消运行），令牌合法时**覆盖自报的 `decidedBy` / `actor`**
  （自报且与令牌不一致 = 400，别让调用方以为自报生效了）；无效令牌 401 且**不降级为匿名**；默认关闭并在启动 WARN 明说
  「审批人名单不是安全边界」。令牌值只来自环境变量（沿用 MCP `auth-token-env` 纪律），装配期四道校验不静默。
  新增 `AIFLOW_AUTH_ENABLED` / `AIFLOW_AUTH_TOKEN_REVIEWER_A` / `AIFLOW_AUTH_TOKEN_ADMIN` 三个环境变量与 `ErrorCode` 401 码（已批准）。
  明确不做：Spring Security / JWT / 角色矩阵 / 全量 API 鉴权（理由见文档 §2.1、§7）。
  实测：全量 `mvn test` **436** 条 0 失败（开启态 `AuthIntegrationTest` 9 条 + 关闭态 `AuthDisabledIntegrationTest` 4 条）；
  真实栈 `script/verify-auth-flow.ps1` **PASS 10/10**（匿名 401 / 坏令牌 401 / 非名单 403 / 自报冲突 400 / 令牌身份 200 且落库
  `decidedBy=reviewer-a` / 被拒三次后审批仍 PENDING）；关闭态 `script/verify-approval-flow.ps1` 回归 PASS；
  控制台浏览器实测匿名点批准报 `40100 该接口需要身份`、填令牌后批准成功且链上决策人 = 主体 id。
  **补的课（写进 AGENTS.md）**：`@RequireIdentity` 第一版是静态注解，关闭态照样拦人，12 条存量用例集体 401 ——
  「关闭 = 整个切片不生效」现在由 `AuthDisabledIntegrationTest` 显式钉住，并作为动这个注解的必跑项。

## M4 记忆、画像与并发加固

- [x] 短期记忆：会话窗口 + 滚动摘要（2026-09-23 交付，**设计见 `docs/memory-design.md`**）。口径按该文档 §2.1 修正为**引擎行为**而不是节点：
  注入在装配期（`llm.chat` / `llm.vision` 共用 `SessionContextProvider`，`tool.call` 不接历史），摘要在**运行收口之后异步**触发
  （虚拟线程执行器，不占会话队列、失败不影响运行）。装配 = `[system(摘要)] + 最近 N 条 + 本轮输入`；攒够 `chunk` 条才折叠，
  **原消息不删**（只推进 `summarized_count` 前缀计数）。窗口注入默认开、摘要默认关（要花模型调用，与 OCR 同口径）。
  接口：`GET /api/v1/sessions/{id}/memory` / `GET .../memory/preview`（**把装配结果摊开** —— 没有它真实栈只能断言「没报错」）/ `POST .../memory/rebuild`。
  可靠性：折叠走条件更新单赢家（`where summarized_count = :expected`）、读失败一律降级为「无记忆」并计入 `readFailures`、
  连续失败到上限停用该会话、摘要 token 计入用户额度。实测：全量 460 条 0 失败；真实栈 `script/verify-memory-flow.ps1` **20/20 PASS**
  （三轮回合后 `summarizedCount=2` / `tokens=200` / 装配 6 条含 system 摘要）；8080 审批回归四条全 PASS；控制台三个记忆按钮浏览器手验通过（零 JS 报错）。
- [x] 长期记忆：事实/偏好抽取 + 向量召回注入（2026-09-23 交付，**设计见 `docs/long-memory-design.md`**）。口径：
  作用域是 **userId**（换个会话照样召回）；抽取走**优先便宜档**并在**运行收口之后异步**触发（节流三条件：会话消息总数 /
  距上次的新增条数 / 最小间隔），归并只看模型判的 `supersedes` + **人工优先**（MANUAL 永不被覆盖）；
  注入 = `[system(长期记忆)] + [system(摘要)] + 窗口 + 本轮`，字符预算不够就**整条丢**（不截断）。
  接口：`GET/POST /api/v1/users/{id}/memories`、`DELETE .../memories/{memoryId}`、`GET .../memories/preview`
  （把命中、分数、丢弃原因与最终注入块摊开）。默认值：**召回开、抽取关**（抽取要调模型花钱，与摘要 / OCR 同口径）。
  实测：全量 493 条 0 失败（本切片新增 33 条）；真实栈 `script/verify-long-memory-flow.ps1` **22/22 PASS**
  （含真实 qwen 抽取落条目）；审批回归四条 + 短期记忆 20/20 全 PASS；控制台长期记忆抽屉浏览器手验通过（零 JS 报错）。
  顺手修掉一个真缺陷：会话装配预览**没带长期记忆**（预览在撒谎），现在预览与真实运行同链路、但**不回写 hits**。
- **M4-3（2026-10-09，A/B 已验收）**：见 `docs/m4-3-design.md`；按 A → B → C → D 逐步推进，M3 云端知识库真实联调并行排期。
  A 已完成共享调度与回放，B 已完成画像与显式偏好；C已完成并发运行、顺序、背压与取消验证，以及当前诊断包的S3a/S3b逐步建连1000规模和慢消费者演练；1000 VU瞬时起跑失败边界保留；D0、D1 mock API已验收。
- [x] M4-3A（2026-10-09 已实现并完成本轮验收）：V9 PostgreSQL 持久调度（消息/运行/任务同事务，审批恢复同队列）+ Redis Stream（最多 2000 条/24h，counter 不自动过期）+ 锁续期/失锁停止 + 跨实例取消。
  验证入口为 `script/verify-distributed-flow.ps1`、`script/verify-dispatch-races.ps1`、`script/verify-redis-events.ps1`、`script/verify-dispatch-failures.ps1`，报告落 `target/m4-3/`。
  本轮报告覆盖同会话顺序、跨会话并行、审批/取消、非法游标、TTL/失锁、崩溃回收与 429 回滚；有界慢消费者与清理由单元测试覆盖；Redis 普通重启、容器重建后回放已验证，真实网络慢消费者容量留 C 压测。
- [x] M4-3B（2026-10-09 已实现并验收，契约见 `docs/profile-design.md`）：V10 用户画像行为统计与停用/逻辑重置；显式偏好复用长期记忆 MANUAL/PREFERENCE，统计只报工作流分布与已记录的对话 token，不冒充语义话题或完整账单。
  本切片新增 37 条测试；最终全量 606 条、0 失败、0 错误、1 跳过，启用了真实 Redis 集成。真实画像 23/23、短期记忆 20/20、审批 4/4、长期记忆手工/召回 21/21，控制台浏览器验收无 JS 错误。证据和未覆盖项见 `docs/m4-3b-validation.md`。
- [x] M4-3C：Gatling mock 压测与故障演练已完成阶段验收：635包全量回归0失败、0错误、1跳过；S0/S1/S2、同会话顺序、背压/取消、当前包S3a同run与S3b不同run的20秒逐步建连1000规模、queue16慢消费者均有独立报告。1000 VU瞬时同时起跑仍保留失败边界，不作生产容量承诺。结果见 `docs/m4-3c-validation.md`。
- [x] M4-3D D0/D1/D2 mock 链路（2026-10-13）：端口、PCM WAV 校验、同步 API、`tts.speak` / `asr.transcribe` 节点、运行内临时句柄与独立音频面板已验收。最终全量 818 条、0 失败、0 错误、1 条预期跳过，离线打包与浏览器验收见 `m4-3d-d2-validation.md`；真实厂商适配与本地语音后置。
- [x] 第一层离线 provider/HTTP 协议抽象（2026-10-14）：云知识库字段映射与受控 HTTP 传输、ASR/TTS 协议 SPI/桥接 provider、本地协议桩及契约测试；定向 85 条、全量 903 条回归通过，离线打包与限制见 `provider-layer1-validation.md`。无需真实额度；不代表厂商联调通过。
- [x] 第二层离线提示词/对话黄金集（2026-10-14）：上下文/工作流/RAG 三组 24/21/18 条、设施 9 条全部通过；补齐失败原因、流式去重与节点收口门禁，修复 RAG 资料分隔符与引用显示标签边界。全量 975 条、0 失败/错误、6 条按开关跳过，离线打包及边界见 `provider-layer2-validation.md`；不依赖真实额度。
- [x] 本地使用与运维收口（2026-10-14）：统一 Check/Start/Status/Stop、固定 mock 与环境隔离、进程身份和监听者核验、合成端到端演示及运行手册。双 PowerShell 启动器门禁各 47/47、真实 PG/Redis 演示各 43/43、失败路径 11/11；不改生产 Java/schema，复用第二层包。验收与后续真实厂商边界见 `local-ops-validation.md`。
- [x] 运行回放与失败定位（2026-10-14）：只读节点尝试摘要分页、归属校验详情与控制台面板；基于 PG 快照查看失败、重试、降级、审批和取消，不重新执行。定向 30/30、全量 998 条（0 失败/错误、6 跳过）、真实 API 23/23、原演示 43/43、浏览器 22 项通过，含两秒延迟旧响应切换检查。见 `run-replay-validation.md`。
- [x] M4-3D D0/D1（2026-10-13）：D0 独立端口、PCM WAV 校验、mock 与能力报告；D1 mock 同步转写/合成 API、严格错误契约、输出复验和 Servlet 上传限额已验收。

验收：压测报告（明确瓶颈与结论）；多实例下同会话仍严格有序；记忆可查看可删除。

## M5（Stretch）声纹与本地全链路

- [ ] 声纹 1:1 确认（CAM++/ECAPA-TDNN sidecar 或云 API）+ 访客权限矩阵；可插拔 provider，未配置时功能自动禁用
- [ ] 本地大模型（vLLM/Ollama）全链路离线模式；本地 ASR/TTS 替代云服务
- M5 第一批实现已完成可离线验证切片：M5-A 已加入 `offline` profile、loopback 出网门禁、非法端口拒绝、未探活降级验证，以及用合成 loopback 桩跑通 local chat/embedding/RAG 成功路径；M5-B 已加入本地 sidecar 的厂商无关 SPI、装配门禁、`/v1` 路径保留和 TTS→ASR loopback 成功、非法响应、超时测试。定向测试与限制见 [`m5-validation.md`](m5-validation.md)；真实模型、真实 ASR/TTS 协议和声纹仍未验收。
- [x] 人工审批节点（超时/升级）与通知 —— **2026-09-22 提前在 M3 收尾阶段交付**（挂起/恢复、超时打回/放行/升级、待办与决策接口、随运行取消都已落地，实测见 M3 一节）；通知已交付 `log` + `webhook` 适配器（异步投递、提交后发、去重、失败只计数、出网过 SSRF 护栏），邮件/IM 明确不做

验收：离线环境下核心对话链路可用；声纹识别失败有明确降级路径（访客模式）。

## 横切事项（贯穿所有里程碑）

- [x] 厂商 extra-body 解析边界（2026-10-15）：固定配置错误诊断并校验 JSON null、顶层 null 和尾随值，正常厂商字段与主干护栏保持；新增 22 条，定向 129 条、全量 1130 条（0 失败/错误、6 跳过）及离线打包通过，见 `endpoint-extra-body-validation.md`。
- [x] chat 流式读取超时与取消分类（2026-10-16）：按 `docs/chat-stream-timeout-design.md` 将现有端点 `timeoutMs` 覆盖至响应体读取，流读取中断按用户取消处理且不降低端点健康；保留已经发出增量后禁止换档的规则。定向 109 条、全量 1134 条（0 失败/错误、6 跳过）及离线打包通过，见 `docs/chat-stream-timeout-validation.md`；真实厂商流式行为后置。
- Embedding 与探活错误边界已验收（2026-10-14）：固定适配器错误诊断并移除上游模型清单回显，不改变向量/探活判定和检索参数；新增 35 条用例，定向 66 条、全量 1054 条（0 失败/错误、6 跳过）和离线打包通过，见 `embedding-probe-error-validation.md`。
- 对话 HTTP 错误边界已验收（2026-10-14）：限制上游错误正文与原始异常向健康状态、降级记录及运行回放传播；新增 21 条回归，定向 33 条、全量 1019 条（0 失败/错误、6 跳过）和离线打包通过，见 `chat-error-validation.md`。只证明本地协议与错误契约，真实厂商联调仍后置。
- [x] 视觉 OCR 错误边界（2026-10-14）：固定 OCR 能力缺失/调用失败诊断，阻断上游正文、凭证、URL 与异常链向文档失败记录和 HTTP 响应传播；仅使用离线 fake router 验证，真实视觉模型联调仍后置，设计见 `vision-ocr-error-boundary.md`，结果见 `vision-ocr-error-validation.md`。
- [x] RAG 检索错误边界（2026-10-14）：`RagRetrievalService` 对可插拔 embedding / retrieval provider 的异常只保留固定分类和来源标识，阻断原始 message、`toString()`、cause 与 suppressed 向检索异常、节点失败结果和日志传播；来源健康检查异常继续按部分降级处理，正常检索与后处理语义不变。设计见 `rag-retrieval-error-boundary.md`，验收见 `rag-retrieval-error-validation.md`。
- 可观测：运行回放面板已验收（`run-replay-validation.md`）；OpenTelemetry 后续按需评估
- 安全：工具沙箱、注入防护、内容审核、脱敏规则
- 合规：数据生命周期（删文件级联删向量、会话归档、声纹删除）
- 评测：RAG 黄金集 + 对话黄金集；prompt/工作流改版必须跑回归

本轮 M4-3A 验收结果与限制见 [m4-3a-validation.md](m4-3a-validation.md)。

本轮 M4-3B 验收结果与限制见 [m4-3b-validation.md](m4-3b-validation.md)。
