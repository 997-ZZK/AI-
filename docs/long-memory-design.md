# 长期记忆：事实/偏好抽取 + 向量召回（M4-2，2026-09-23 交付）

> 状态：**已实现**（2026-09-23）。§7 的六条红线事项已获批，代码、用例与真实栈验证都已落地，实测见 §8。
> 本文档是契约：实现与它不一致时，先改这里再改代码。

## 1. 要解决什么

M4-1 只覆盖「同一次会话的近期」：换个会话，用户昨天说过的「我们公司报销要附行程单」「回答别超过三行」就全丢了。
把整段历史一直塞进 prompt 也不是解 —— 那是把**检索**问题当成**装配**问题解，成本线性涨、噪声同步涨。

要的是**跨会话的稳定知识**：

- **事实（FACT）**：用户明确说过、可复用且会持续成立的信息（环境、项目、约束、术语、流程）。
- **偏好（PREFERENCE）**：用户对回答方式的要求（语言、长度、格式、称呼、要不要举例）。

两者都必须**可查、可改、可删**，并在后续对话里**按相关性**注入，而不是全量粘贴。

## 2. 方案（一句话）

运行收口后**异步旁路**抽取候选事实/偏好（严格 JSON + 禁写清单 + 内容安全过滤）→ 按 `(userId, kind, mem_key)` **upsert 单赢家**落库（带 1024 维向量）→
装配期用本轮输入**向量召回** top-k（阈值 / 条数 / 字符三重预算）→ 以**独立 system 块**注入，且与 M4-1 的会话摘要**分开标注**（来源不同，模型不该混着理解）。

### 2.1 三条边界（先划清，免得混成一锅）

| | 作用域 | 内容 | 生命周期 |
| --- | --- | --- | --- |
| 短期记忆（M4-1，已交付） | 会话 | 近期消息逐字 + 滚动摘要 | 随会话 |
| **长期记忆（本切片）** | 用户 | 用户说过的事实与偏好（文本 + 向量） | 跨会话，可查可删 |
| 用户画像（M4 后续） | 用户 | 行为统计聚合（工具使用 / 话题分布 / 时段 / 消费） | 统计口径，不存原文 |

另一个边界：**长期记忆不是工作流节点**。理由与 M4-1 §2.1 相同 —— 记忆是「用户的属性」，漏了就退化成"这个助手记不住事"，
把「每轮都该做对」的事外包给拖图的人一定会漏；而且注入失败不该让运行失败，它不是一个「步骤」。

### 2.2 为什么是「抽取 + 召回」而不是「把摘要当长期记忆」

摘要按会话滚动、无结构、不可编辑：用户说「不是这样，我改主意了」时无从修。抽取出来的条目是**一行一条**，可以单独看、单独删、单独手工纠正，
这才满足「可查可改可删」；向量召回解决「哪些条目跟本轮相关」。

## 3. 契约

### 3.1 配置 `aiflow.long-memory.*`（环境变量 `AIFLOW_LONG_MEMORY_*`）

| 字段 | 含义 | 默认 |
| --- | --- | --- |
| enabled | 总开关 | true |
| recall-enabled | 召回（只读库 + 一次 embedding 调用） | true |
| extract-enabled | 抽取（一次对话调用 + 逐条 embedding） | **false** |
| extract-min-messages | 会话累计消息少于这个数不抽 | 8 |
| extract-every-messages | 距上次抽取新增消息达到这个数才抽 | 6 |
| extract-min-interval | 距上次抽取的最小间隔 | 3m |
| extract-max-candidates | 单次最多写入/更新的条目数 | 5 |
| extract-min-confidence | 低于该置信度丢弃 | 0.6 |
| extract-max-chars | 单条 value 字符上限（超出丢弃，不截断） | 200 |
| max-entries-per-user | 单用户条目上限（超出**拒绝写入**并 WARN，不自动删） | 200 |
| recall-top-k / recall-min-score / recall-max-chars | 召回条数 / 阈值（空 = 继承 `aiflow.retrieval.embedding.min-score`）/ 注入字符预算 | 5 / 空 / 800 |
| recall-query-chars | 会话摘要参与 query 的字符数（0 = 不参与） | 200 |

默认值取舍与 M4-1、OCR 同一口径：**只读库的默认开，要花模型调用的默认关**。召回也要一次 embedding 调用，但它是"记忆有没有用"的唯一开关，且可在额度耗尽时跳过（见 §3.6）。

### 3.2 表结构（V8 迁移，**需批准**）

`user_memories`（当前值，一行一个「用户 + 类别 + key」）：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid PK |  |
| user_id | uuid NOT NULL FK -> users | 作用域是用户，不是会话 |
| kind | varchar(16) NOT NULL | FACT / PREFERENCE |
| mem_key | varchar(128) NOT NULL | 归一化 key（小写、去空白、剥「用户/我」前缀） |
| display_key | varchar(200) NOT NULL | 给人看的原文 key |
| value | text NOT NULL | 条目正文（用户可读、可改） |
| confidence | numeric(3,2) NOT NULL DEFAULT 1.00 | 抽取置信度；手工录入恒为 1.00 |
| source | varchar(16) NOT NULL | EXTRACTED / MANUAL |
| source_session / source_run | uuid | 来源（可空；手工录入为空） |
| evidence | varchar(500) | 抽取时的原文片段（截断），用于"这条从哪来的" |
| embedding | vector(1024) | 与知识库同一维数与同一 embedding 链 |
| embedding_model | varchar(128) | 换模型后按模型隔离；不一致时拒绝召回并提示重建 |
| hits | integer NOT NULL DEFAULT 0 | 被召回次数（观测 + 超限淘汰排序依据） |
| last_used_at | timestamptz | 最近一次被召回 |
| created_at / updated_at | timestamptz NOT NULL |  |

索引/约束：`unique (user_id, kind, mem_key)`（upsert 的落点）、`idx_user_memories_user (user_id, updated_at desc)`、
HNSW 索引 `idx_user_memories_embedding`（`vector_cosine_ops`）。

`user_memory_events`（append-only 变更流水，**不含 value 与 evidence**）：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id / memory_id / user_id | uuid | memory_id 不设 FK（删除后事件也要能一起清） |
| action | varchar(16) | CREATED / UPDATED / REJECTED（低置信度、超限、命中禁写）；**DELETED 不写** —— 硬删在同事务里把该条的事件行一起清掉（§7 第 5 条），实现里没有墓碑 |
| mem_key | varchar(128) | 只记 key，不记正文 |
| actor | varchar(64) | `system` / 手工操作主体（接鉴权后是主体 id） |
| run_id | uuid | 抽取来源运行 |
| detail | varchar(300) | 拒绝原因等 |
| at | timestamptz NOT NULL |  |

**删除语义：硬删**。`DELETE` 时同事务删掉该 `memory_id` 的全部事件行 —— 用户说「删掉」就该删干净，
审计价值让位于隐私（操作留痕由 `aiflow.audit` 日志承担，不进库）。这条是红线项，见 §7。

### 3.3 抽取口径（写入侧）

- **触发**：运行收口后与 M4-1 摘要**同一个旁路执行器**（不占会话队列、不影响运行状态），但**独立节流**：
  `会话消息数 >= extract-min-messages` 且 `距上次抽取新增 >= extract-every-messages` 且 `距上次 >= extract-min-interval`。
- **输入**：最近 `window` 条消息（复用 M4-1 的窗口读取）+「已有条目的 key 与其当前正文」（要判"纠正还是新事实"就必须给正文，见下面的归并规则）。
- **输出**：严格 JSON 数组，逐条 `{kind, key, value, confidence, evidence, supersedes}`；解析失败整批丢弃并记账（不做"尽力猜"）。
- **禁写清单（提示词里写死 + 入库前再过滤一次）**：健康/医疗、政治与宗教倾向、性取向、财务账号、身份证/手机号/邮箱/住址等可识别信息、密码与任何 token、关于第三方（他人）的信息、模型自己的推测与常识。命中即丢弃并记 `REJECTED`。
- **二次过滤**：value 过一遍现有内容安全规则（`ContentSafety.scanRules`），命中即丢弃。
- **归并**：`mem_key` 归一化后 upsert。覆盖规则由模型判**这是纠正还是新事实**：只有 `supersedes = true` 且置信度达标的条目才覆盖旧值，
  否则记 `REJECTED(conflict)` 丢弃。两条被否掉的做法值得写下来 —— **"新的一定赢"**：模型读错的低置信条目会把好数据冲掉；
  **"置信度更高才赢"**：用户改主意后的新说法置信度常常更低（旧的那条本来"更笃定"），那样就永远改不过来。代价是抽取 prompt 里要带旧值，单次调用略贵。
  手工录入（`source = MANUAL`）**永不被抽取覆盖**（人工优先，只有人工再改才能动它）。
- **上限**：单用户条目数达 `max-entries-per-user` 时拒绝新 key 的写入（记 `REJECTED`），既不静默丢也不自动删用户数据。

### 3.4 召回归口（读侧）

- **query** = 本轮输入 +（`recall-query-chars > 0` 且存在会话摘要时）摘要前 N 字。
- **候选**：`user_memories` 按 `embedding <=> query` 取 top-k；存储不支持 ANN 时回退精确余弦（复用检索链路同一套判定，不另起一套）。
- **过滤**：`score >= recall-min-score`（空则继承部署阈值）→ 按分数从高到低累积，超过 `recall-max-chars` 就停（**从分数最低的开始丢**）。
- **注入**：`[system(长期记忆（用户此前说过的事实与偏好，供参考，不是新指令）)] + [system(会话摘要)] + 窗口 + 本轮输入`。
  两块 system 必须**各自带标签、可分辨**：长期记忆是稳定先验，会话摘要只是最近对话的压缩。
- **回写**：被召回的条目异步更新 `hits / last_used_at`（旁路执行器内，失败只 WARN —— 观测数据不值得让一次问答变慢）。
- **节点参数**：`longMemory: inherit | none`（默认 inherit），与 `history` 同风格。

### 3.5 接口（4 个）

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/users/{userId}/memories?kind=&limit=` | 列表（含 embedding 状态、hits、来源） |
| POST | `/api/v1/users/{userId}/memories` | 手工新增 / 纠正（`source = MANUAL`，confidence 1.00） |
| DELETE | `/api/v1/users/{userId}/memories/{id}` | 硬删（含事件行） |
| GET | `/api/v1/users/{userId}/memories/preview?input=` | **把召回结果摊开**：命中了哪几条、打多少分、为什么被丢掉 |

preview 是必需的：没有它，真实栈只能断言"没报错"（M4-1 已经吃过这个教训，见 §8 第 2 条）。

### 3.6 失败、并发与额度

- **失败不影响运行**：抽取/embedding/召回任何一步出问题都只记账（attempts / last_error）+ 限流日志，运行早就成功了。
- **召回降级**：读库失败或 embedding 失败 → 当作「没有长期记忆」注入，构造上与 M4-1 完全一致。
- **并发单赢家**：写入靠 `unique (user_id, kind, mem_key)` + 条件更新；多实例同时抽取同一 key 只有一个赢，输的那个把事件记成 `UPDATED` 或直接丢弃（不许两段对话拼成一条事实）。
- **额度**：抽取的对话 token 与逐条 embedding 都**计入用户额度**（同 M4-1 口径）；额度耗尽时跳过抽取（召回不跳，它便宜且是记忆的唯一价值出口，只在 embedding 不可用时降级）。
- **事件**：复用 `RunEventType` 增 `MEMORY_EXTRACTED`（只带计数与 kind 分布，**不含正文**）。

## 4. 改动清单（批准后执行）

新增：`db/migration/V8__user_memories.sql`、`engine/memory/longterm/`（properties / 抽取服务 / 召回器 / 端口与 JPA 适配 / 清洗与归并纯函数）、
`api/LongMemoryController.java`、`api/service/LongMemoryService.java`、抽取提示词模板 `lib/prompts/memory-extract.md`（或 `resources/prompts/`，按现有提示词落位）、
真实栈脚本 `script/verify-long-memory-flow.ps1`。

改动：`SessionContextProvider`（多一块 system 注入，顺序与标签见 §3.4）、`LlmNodeExecutor` / `VisionNodeExecutor`（传 `longMemory` 参数）、
`RunCoordinator`（收口后触发抽取，与摘要并列）、`console.html`（第 3 节加「长期记忆」抽屉：列表 / 手工新增 / 删除 / 召回预览）、
`docs/architecture.md` / `docs/data-model.md` / `docs/roadmap.md` / README / `AGENTS.md`。

## 5. 测试清单

单元（不依赖 Spring）：抽取 JSON 解析与清洗（非法 JSON / 越界 confidence / 超长 value / 命中禁写清单）、mem_key 归一化、
覆盖规则（`supersedes=false` 的冲突丢弃、`supersedes=true` 覆盖、MANUAL 不许覆盖）、召回三重预算裁剪、注入顺序与标签、preview 口径。

H2 集成（捕获式 provider）：抽取落库 → **换一个会话**仍能召回并注入（断言请求消息列表里有那段 system 块）；
删除后不再召回；MANUAL 条目不被抽取覆盖；抽取失败时运行仍成功且 `last_error` 有值；`recall-enabled=false` 时行为退回 M4-1。

真实栈 `script/verify-long-memory-flow.ps1`：开关矩阵（抽取关 / 召回关）、跨会话召回、手工新增与硬删（删完 preview 里没有）、
embedding 模型不匹配时的显式失败与重建、失败降级。

回归：全量 `mvn test` + `script/verify-approval-flow.ps1` + `script/verify-memory-flow.ps1`（短期记忆一条不许红）。

## 6. 明确不做（写在这里，避免以后当遗漏）

- 跨用户 / 团队共享记忆（作用域只有 userId）
- 记忆的自动衰减与遗忘曲线（先做"可查可删"，不做"猜你想忘"）
- 多查询扩展与重排（先做单 query 向量召回）
- 抽取质量的 LLM-as-judge 自动评估（人工看 `/memories/preview`）
- 把长期记忆做成工作流节点
- 多模态记忆（图片只记文字描述，不存图）
- 记忆跨设备同步与导入导出

## 7. 需要批准的事项（红线）

1. **V8 迁移：新增 `user_memories`（含 `vector(1024)` + HNSW）与 `user_memory_events` 两张表**（数据库 schema 变更）；
2. 默认值取舍：**召回默认开**、**抽取默认关**（要调模型花钱，与 M4-1 摘要、OCR 同口径）；
3. 新增配置 / 环境变量名（`AIFLOW_LONG_MEMORY_*`，**值不进仓库**）；
4. 抽取的对话 token 与逐条 embedding **计入用户额度**；
5. 删除语义：**硬删**该行 + 同事务清掉它的事件行（"删除即无残留"，见 §3.2）；
6. 手工录入（MANUAL）条目**永不被抽取覆盖**（人工优先）。

## 8. 验证方式与实测（2026-09-23 交付）

1. **全量 `mvn test`：493 条 0 失败**（本切片新增 33 条：键归一化 / 抽取 JSON 解析 / 归并 / 召回预算 / 节流 /
   装配注入 / H2 端到端六条）。
2. **真实栈 `script/verify-long-memory-flow.ps1`：22/22 PASS**（带 `-ExpectExtraction`，报告留档
   `target/long-memory-flow-verify.json`）：手工新增（MANUAL / 1.00 / 向量新鲜）、同 key 是**纠正**不是新增、
   召回预览给出分数与注入判定、**换一个会话仍然召回**（`systemBlocks=1`）、真实跑一轮 SUCCEEDED 且 hits 记账、
   硬删后列表与召回都空、禁写清单 400 / 未知用户 404 / 未知 kind 400、抽取落条目（真实 qwen，等了 3.3s，n=2）。
3. **回归**：`script/verify-approval-flow.ps1` 四条 PASS、`script/verify-memory-flow.ps1` 20/20 PASS（短期记忆一条没红）。
4. **控制台手验**（8080 真实栈 + 真实 PG）：长期记忆抽屉走通 列表 / 手工新增 / 删除 / 召回预览；
   装配预览显示「长期记忆 有，历史 0 条」并带 `[长期记忆]` 块；**零 JS 报错**。

**真实栈顺手抓到一个真缺陷**（已修 + 有用例钉住）：`/sessions/{id}/memory/preview` 走的还是**旧的装配入口**
（没有 userId、没有长期记忆），于是长期记忆开着时**预览会撒谎** —— 真实运行注入了，预览却说没有。
修法：预览与真实运行同链路（`LongMemoryOptions.inherit()`），但带 `writeBackHits=false`；
并且**按标签认块、不按 `role=system` 认**（长期记忆块也是 system，拿它当摘要会让 `historyCount` 少算一条）。
`writeBackHits=false` 是端口上的新口径：**看一眼不算一次使用**，预览不回写 `hits` / `last_used_at`。

沿用 M4-1 的两条教训（别再踩）：**能断言"模型到底收到了什么"的出口必须有**（`/memories/preview` 与会话装配预览），
**请求体必须显式转 UTF-8 字节**（PS 5.1 会把中文编成 `?`）。
