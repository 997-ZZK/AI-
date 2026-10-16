# 短期记忆：会话窗口 + 滚动摘要（M4-1，2026-09-22 定稿，2026-09-23 交付）

> 状态：**已实现并验证**（2026-09-23，实测数字见 §8）。四项红线（V7 迁移、默认值取舍、环境变量名、摘要 token 计入额度）
> 已于 2026-09-23 全部获批。
> 本文档是契约：实现与它不一致时，先改这里再改代码。

## 1. 要解决什么

今天的 `llm.chat` 发出去的是 **一条** `user(prompt)`（`LlmNodeExecutor` 里写死 `List.of(ChatMessage.user(prompt))`）：
同一会话的第二轮，「那它呢」「换成图片版」这类指代没有任何依据；换工作流也一样，**谁都没有历史**。

反方向也不对：把整段会话塞进 prompt，成本随轮次线性涨、迟早撞上下文窗口，旧信息还变成噪声。
所以要的是**有界**的上下文：近期逐字保留，远期压缩成摘要，且压缩过程不能让主链路变慢或变脆。

## 2. 方案（一句话）

引擎在装配期自动拼出 `[system(摘要)] + [最近 N 条消息] + [本轮输入]`；会话攒够新消息后，在**同一个会话锁内、异步旁路**把旧消息折进滚动摘要（原消息不删）。

### 2.1 为什么不是「摘要节点」（对 roadmap 原措辞的修正）

roadmap M4 写的是「会话窗口 + 滚动摘要**节点**」。建议改成引擎行为，理由：

- 记忆是**会话的属性**，不是工作流的属性。让每个拖图的人自己加一个摘要节点，等于把「每轮都该做、漏了就退化」的事外包给使用者，而且没人会记得加。
- 摘要是一次**旁路维护**调用：失败不该让运行失败、成功也不改运行结果。图里的节点是「这一步的副作用」，语义不匹配。
- 结论：**注入**由引擎在装配期做（`llm.chat` / `vision.chat` / `tool.call` 共用一份装配器），**摘要**由引擎在运行收口后触发。不新增节点类型，工作流定义零改动。（批准后我按这个口径改 roadmap 措辞。）

## 3. 契约

### 3.1 配置 `aiflow.memory.*`

| 字段 | 含义 | 默认 |
| --- | --- | --- |
| `enabled` | 窗口注入总开关 | `${AIFLOW_MEMORY_ENABLED:true}`（纯读库、不花模型调用，默认开） |
| `window.size` | 注入的历史条数（user + assistant 合计） | `8` |
| `window.max-chars` | 历史总量上限（超了从最老的开始丢） | `4000` |
| `summary.enabled` | 滚动摘要开关 | `${AIFLOW_MEMORY_SUMMARY_ENABLED:false}`（**要调模型、要花钱，默认关** —— 与 OCR 同一取舍） |
| `summary.chunk-size` | 攒够多少条新消息折叠一次 | `6` |
| `summary.max-input-chars` | 单次折叠的输入上限 | `8000` |
| `summary.max-chars` | 摘要正文上限（超了截断） | `1200` |
| `summary.prefer-cheap-tiers` | 摘要调用是否优先便宜档位（复用 `aiflow.provider.cheap-tiers`） | `true` |
| `summary.max-attempts` | 连续失败多少次后**停用该会话**的摘要（避免坏会话每轮烧一次调用） | `5` |

### 3.2 表 `session_memories`（V7，**红线：schema 变更，待批**）

```sql
create table session_memories (
    session_id        uuid primary key references sessions (id) on delete cascade,
    summary           text,                                   -- 滚动摘要正文；null = 还没生成
    summarized_count  integer not null default 0,             -- 已折进摘要的**最早 N 条**消息（前缀计数）
    summary_chars     integer not null default 0,
    model             varchar(64),                            -- 生成它的档位 id（换档后能看出来）
    tokens            integer not null default 0,             -- 累计为摘要花掉的 token（诚实记账）
    attempts          integer not null default 0,             -- 连续失败次数；成功清零
    last_error        varchar(400),
    updated_at        timestamptz not null
);
```

**为什么是「前缀计数」而不是「水位消息 id」**：`messages.id` 是随机 UUID，没有单调性；`created_at` 只有微秒精度，
同一微秒内的两条会并列。而 `messages` 是**只追加**表，`order by created_at, id` 是稳定全序 —— 于是
「最老的 N 条已经折进摘要」这个计数是精确的，且**不需要动 `messages` 表**（少一次对存量表的迁移风险）。

自愈：`summarized_count` 超过「可折叠上限」时（消息被外部删除、会话被改）重置为 0 并记 WARN —— 宁可重摘要一遍，
也不要相信一个自相矛盾的账本。

### 3.3 装配口径（引擎侧，纯读）

```
[system] 会话摘要（存在时才加）
[user] ...          <- 窗口内最早一条
[assistant] ...
[user] 本轮输入      <- 由节点自己提供（现状不变）
```

- **不含当前这轮**的 user 消息（它就是要发的 prompt 本体）；不含空内容；不含 `system` 类消息（避免二次注入）。
- 历史里的图片降级为 `[图片]` 占位（多模态历史不在本切片；模型不需要重新看上一轮的图）。
- 节点参数可覆盖：`history: inherit | none | <n>`、`includeSummary: true|false`（纯函数式工作流可整体关掉）。
- **绝不让记忆拖垮主链路**：读库异常 -> 降级为「无历史」+ 计数 + WARN，运行照跑。
- 分层：`engine/memory/SessionMemoryPort`（接口，engine 层）+ `persistence/JpaSessionMemoryAdapter`（实现）。
  节点层不碰 JPA（遵守 AGENTS.md 目录约定）。

### 3.4 滚动摘要的触发与并发

- 触发点：`RunCoordinator` 在 `saveReply` 之后、**仍持会话锁**、丢给同一个 `SessionDispatcher` 串行执行器
  （不占请求线程、不加长用户等待）。
- 条件：`summary.enabled && (总条数 - window.size - summarizedCount) >= chunk-size`。
- 生成：`已有摘要 + 本次折叠窗口 -> 新摘要`（**增量合并**，不重读已折叠的消息；没有摘要时直接摘要本段）。
- 单赢家：`update session_memories set ... where session_id = ? and summarized_count = :expected`，
  抢不到就放弃并计数（多实例下同会话可能被两个运行先后碰到）。
- 失败：运行**照常成功**；`attempts+1`、`last_error` 留痕（只记错误类型与变量名，不记摘要正文），下一轮再试；
  连续失败到 `max-attempts` 就把该会话的摘要停用并 WARN。
- 成本：摘要调用计入**该用户额度**（`TokenBudgetService.charge`），累计值写进 `session_memories.tokens`；
  额度已耗尽时**跳过**摘要（不降级、不失败）。

### 3.5 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/sessions/{id}/memory` | 状态：`summary` / `summarizedCount` / `windowSize` / `tokens` / `attempts` / `lastError` / `model` |
| GET | `/api/v1/sessions/{id}/memory/preview?input=...` | **装配结果**（每条 role + 字符数 + 是否截断 + 摘要全文 + `summaryIncluded` / `longMemoryIncluded`）—— 没有它，真实栈没法证明「模型到底看到了什么」；**与真实装配同链路，但不回写任何观测数据**（M4-2 起长期记忆也走这里，召回的 hits 不因预览而变） |
| POST | `/api/v1/sessions/{id}/memory/rebuild` | 清空摘要与前缀计数，要求重算（换档位 / 改配置后用；幂等） |

- SSE 新增 `MEMORY_SUMMARIZED`（带 `summarizedCount` / `tokens` / `model`），控制台第 3 节会话区加「记忆」抽屉（摘要 + 水位 + 装配预览）。
- 日志与事件**不写摘要正文**（用户隐私），只写条数、字符数、token。

## 4. 实现分解

新增：`engine/memory/SessionMemoryPort`（读窗口 / 读摘要 / 条件推进）、`engine/memory/SessionContextAssembler`（纯函数，便于单测）、
`engine/memory/MemoryProperties`、`persistence/entity/SessionMemoryEntity` + `repo/SessionMemoryRepository`（含条件更新）、
`persistence/JpaSessionMemoryAdapter`、`api/MemoryController`、`api/service/SessionMemoryService`（触发与单赢家）、
`resources/db/migration/V7__session_memories.sql`。

改动：`LlmNodeExecutor` / `VisionNodeExecutor`（改用装配器；`tool.call` **不接**历史 —— 它发的是工具请求，不是对话）、
`RunCoordinator`（收口后触发摘要）、新增 `MemoryController`（状态 / 预览 / 重建三个接口）、`console.html`（记忆面板）、`docs/architecture.md` / `docs/data-model.md` / `docs/roadmap.md` / README。

## 5. 测试清单

单元（不依赖 Spring）：窗口选择（排序 / 条数与字符双上限 / 角色映射 / 图片占位 / 空内容过滤）；
装配器（摘要置顶、节点覆盖 `none` 与 `includeSummary=false`）；触发判定（阈值边界、`max-attempts` 停用）；
增量摘要 prompt 组装；`summarized_count` 越界自愈。

H2 集成：**第二轮真的带上一轮**（用捕获式 provider 断言请求消息列表，不是断言「没报错」）；
摘要触发一次并把 `summarized_count` 推进；两个并发运行只有一个推进成功（条件更新）；
失败路径（provider 抛错 -> 运行仍成功 + `last_error` + `attempts+1`）；`enabled=false` 时行为退回现状（零历史）。

真实栈 `script/verify-memory-flow.ps1`：三轮对话 -> 每轮 `/memory/preview` 断言（**空会话**零历史且只有本轮输入、
首轮后 2 条、二轮后 4 条且当前输入只出现一次、摘要开启后第三轮带 system 摘要）；`/memory` 计数与 token 递增、`rebuild` 清摘要保留 token。

回归：全量 `mvn test`；`enabled` 默认值下的存量用例必须一条不红（这道门第一次跑就要过）。

## 6. 明确不做（写在这里，避免以后当遗漏）

- 长期记忆（事实 / 偏好抽取入库 + 向量召回）与跨会话记忆 -> M4-2
- 历史消息的向量检索（本切片只做窗口 + 摘要；先用最便宜的机制解决问题）
- 摘要质量自动评估（人工看 `/memory/preview`；不做 LLM-as-judge）
- 多模态历史（图片只留 `[图片]` 占位）
- 精确 token 计量（按字符阈值控制；真实用量以 provider 回执为准）
- 不引入向量库存历史、不改 `messages` 表结构

## 7. 需要批准的事项（红线，2026-09-23 全部批准）

1. **V7 迁移：新增 `session_memories` 表**（数据库 schema 变更）；
2. 默认值取舍：**窗口注入默认开**（读库不花钱）、**滚动摘要默认关**（要调模型花钱，与 OCR 同口径）；
3. 新增配置 / 环境变量名（`AIFLOW_MEMORY_ENABLED` / `AIFLOW_MEMORY_SUMMARY_ENABLED` 等，值不进仓库）；
4. 摘要调用的 token **计入用户额度**（会真实消耗额度，额度耗尽时跳过摘要）。

## 8. 验证结果（2026-09-23 实测）

- 全量 `mvn test`：**460 条 / 0 失败 / 1 跳过 / BUILD SUCCESS**（`target/package-m4-1c.log`；M3 收尾是 436 条，本轮 +24）。
- 定向：`SessionContextProviderTest` 9 + `LlmNodeHistoryInjectionTest` 3 + `SessionMemoryServiceTest` 8 +
  `SessionMemoryIntegrationTest` 4 = **24 条全绿**。
- 真实栈（PG 15432 + mock provider，临时起的第二个实例：摘要开、`window-size=4`、`summary.chunk-size=2`）：
  `script/verify-memory-flow.ps1` **20/20 PASS**（`target/memory-flow-verify.json`）。关键读数：空会话装配 1 条（无历史 / 无摘要）；
  首轮后 2 条；二轮后 4 条且当前输入只出现一次；第三轮后 `summarizedCount=2`、`tokens=200`、`model=mock`、`summaryChars=217`；
  装配 = system 摘要 + 窗口 4 条 + 本轮输入 = 6 条；`rebuild` 清摘要保留 token；未知会话 404。
- 回归（同 jar，鉴权关、摘要默认关）：`script/verify-approval-flow.ps1` 四条断言全 PASS（挂起 / 不持会话锁 / 批准恢复 / 取消收口）。
- 控制台手验（真实栈 + 浏览器）：第 3 节「记忆状态 / 装配预览 / 重建摘要」三个按钮都跑通，**零 JS 报错**；
  也正是这次手验暴露了下面第 3 条计数缺陷（连说两句「继续」时面板说 3 条、列表里明明 4 条）。

交付过程踩到并修掉的三个坑（都值得留住）：

1. **摘要判定必须在 `loadOrCreate` 之前**：第一版先建行再判断「够不够 chunk」，于是第一轮运行就插了一条空记忆行 ——
   集成测试「16 条之前不该折叠」直接抓住（断言 `findById().isEmpty()`）。现在先 `countBySessionId` 算 foldable，不够就直接返回。
2. **真实栈脚本必须显式转 UTF-8 字节**：第一版 `verify-memory-flow.ps1` 用 PS 5.1 直接传字符串，中文被按 ASCII 编成 `?`，
   三轮的 `clientMsgId` 因此撞车、被幂等去重成了同一轮 —— 于是「第二轮没历史」「摘要没折」看起来都像实现坏了。
   教训写进 `AGENTS.md`：**能看到「模型到底收到了什么」（`/memory/preview`）的断言才有价值**，否则这种假通过会一路绿下去。

3. **preview 的「历史条数」不能按内容判定**：第一版用「内容 ≠ 本轮输入」来数历史，于是用户连着说两句同样的话时，
   上一轮那条会被当成「本轮输入」排除掉、少报一条（控制台手验一眼看出来）。现在按**装配形状**数：
   `历史 = 装配结果条数 - 本轮输入 - 摘要 - 长期记忆块`，并补了一条单测钉住（`previewCountsHistoryByAssemblyShapeNotByContent`）。
   M4-2 加了第二块 system 之后，认块的方式也从「`role=system` 就算摘要」改成**按标签认**：长期记忆块同样是 system，
   用它当摘要会让历史条数少算一条 —— 真实栈脚本正是这么抓到的（同一处还漏了传 userId，于是预览在长期记忆开着时直接撒谎）。

另外：打包要**先停实例再 `mvn package`**（运行中的实例锁住 jar，报 `Unable to rename ... .jar.original`）。
