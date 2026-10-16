# M4-3B：用户画像与显式偏好

状态：2026-10-09 已实现并验收。按 `m4-3-design.md` §4 的 B 方案交付 V10、画像统计、停用/逻辑重置与显式偏好，结果见 [m4-3b-validation.md](m4-3b-validation.md)。C/D 未进入实现。

## 1. 结构与事实来源

- `engine/profile` 放偏好端口、纯规则和统计口径；`engine/persistence/entity`、`repo` 放画像控制状态，JPA 偏好适配放 `engine/persistence`。
- `api/service/ProfileService` 编排控制状态和实时统计，`api/ProfileController` 提供接口；控制台继续使用 `static/console.html`。
- V10 `user_profiles` 只存控制状态，不复制运行、审计、长期记忆或偏好正文，不保存聚合 JSON 快照。
- 新验收入口 `script/verify-profile-flow.ps1`，UTF-8 单 BOM、兼容 PowerShell 5.1。合成数据和报告放 `target/m4-3b/`，不清理历史文件或记录。

## 2. V10 与并发控制

| 列 | 类型 | 语义 |
| --- | --- | --- |
| user_id | UUID PK FK → users | 一用户一行 |
| aggregation_enabled | boolean NOT NULL DEFAULT false | 控制行为统计及画像偏好应用 |
| statistics_since | timestamptz NOT NULL | 本次启用/重置统计起点 |
| timezone | varchar(64) NOT NULL DEFAULT 'UTC' | 合法 `ZoneId` |
| version | bigint NOT NULL DEFAULT 0 | 控制状态修订号，不作为统计缓存版本 |
| created_at / updated_at | timestamptz NOT NULL | 状态行创建/修改时间 |

不存在状态行等同于关闭、UTC、version=0；GET 不建行、不自动启用。写接口以用户行锁序列化，并校验调用方 `expectedVersion`；创建或更新后 version 递增。旧版本返回 409，避免旧页面在重置后覆盖新状态。

从关闭变为开启时将 `statistics_since` 设为当下；持续开启时只改时区不得重置起点。停用后统计返回空视图，且不应用画像偏好。DELETE 为逻辑重置：停用、起点更新为当下、version 递增，保留状态行及所有原运行、审计和长期记忆。再次启用从新的起点开始，不恢复旧统计。

## 3. API

所有响应沿用 `ApiResponse`；用户不存在 404、非法时区/版本/请求 400、版本冲突 409。

| 接口 | 输入 | 行为 |
| --- | --- | --- |
| GET `/api/v1/users/{userId}/profile` | 无 | 控制状态及实时统计；关闭态为空统计，读取不写 |
| PUT 同路径 | `aggregationEnabled`、`timezone`、`expectedVersion` | 更新启用状态/时区；版本必须匹配 |
| DELETE 同路径 | query `expectedVersion` | 重置视图并停用，保留源记录；版本必须匹配 |
| GET `.../profile/preview` | 可选 `profilePreference=inherit/none`、`model` | 与节点共用偏好规则，返回生效/忽略原因、来源、注入块和 cheap-first 决策 |

统计结果只展示行为事实，不输出用户消息、工具参数或推断的敏感属性。当前鉴权没有 Subject→userId 映射，B 延续本地开发边界，不宣称完整多用户隔离。

## 4. 聚合口径

启用时使用 `[max(now - 30天, statistics_since), now]`，按 UTC Instant 过滤，时段再转画像时区。实时查询，无缓存。

| 统计 | 时间和事实来源 | 边界 |
| --- | --- | --- |
| 运行、工作流、时段分布 | `workflow_runs.id / created_at`，按 userId | 审批恢复不因 started_at 改写而重复计数；WAITING_APPROVAL 单列，只有 SUCCEEDED 算成功 |
| 工具使用 | 持久 `tool_audit_events.at / user_id` | SUCCESS、REPLAYED、FAILED、DENIED 分列；复用不是新调用 |
| 已记录对话 token | 本用户 `llm.chat/llm.vision` 节点尝试、`node_runs.started_at` 与 output.tokensUsed | 每次尝试分别统计；缺失、非整数、负数均计 unknown；provider=mock 与非空真实 provider 分列，缺 provider 单列未知来源 |

工具统计同时返回持久审计是否开启、保留天数、待写数、丢写数、失败批次；这些计数为本实例诊断，不能声称整个平台完整。落库关闭时不拿进程内缓冲伪装历史统计。

token 不含摘要、长期抽取、embedding 或未留 output 的调用，unknown 不填 0。这是已记录的对话用量，不是消费账单。工作流分布不是语义话题分类；当日额度继续走原预算接口。

## 5. 显式偏好与装配

偏好真值复用 `user_memories`，只读取 `source=MANUAL`、`kind=PREFERENCE` 的两个规范槽位；写入和读取共同使用 `MemoryKeys.normalize`。

| key | 值白名单 | 效果 |
| --- | --- | --- |
| response-style | concise / balanced / detailed | 映射为固定回复风格说明，不注入任意偏好正文 |
| model-policy | default / cheap-first | default 沿原路由；cheap-first 仅提升已配置便宜档位优先级 |

未知枚举、FACT、EXTRACTED 条目不应用；预览显示原因。读取失败降级为不应用画像偏好，不修改运行终态、不输出原始异常/正文。画像停用不改变 M4-2 长期记忆召回，不能将“停用画像”说成“删除长期记忆”。

`llm.chat`、`llm.vision` 共用一次详细装配结果，避免注入与路由分别重读产生不同决策。顺序为 `[system(画像偏好)] + [system(长期记忆)] + [system(摘要)] + 窗口 + 本轮`；画像说明明确当前输入和工作流要求优先。

节点参数 `profilePreference=none` 停止画像偏好应用，独立于 history/longMemory 开关。显式非空 model 不受画像 cheap-first 改档；预算 SOFT 的 cheap 优先仍然有效，硬额度、能力约束、熔断、安全及工具权限保持原有判定。画像不参与摘要、抽取、工具节点的策略。

会话记忆预览沿同一装配链，新增 `profileIncluded`，historyCount 扣除独立画像标签块。所有预览不回写 hits 或状态。

预览识别画像、长期记忆与摘要块时，必须同时满足 `role=system` 和对应标签前缀；用户/助手正文即使与标签同名仍是普通消息，不能改变块标记或导致负数 historyCount。

## 6. 验收

- 纯规则：规范 key、白名单、MANUAL 优先、停用、工作流禁用、显式 model、预算优先级、失败关闭。
- H2/API：GET 不建行；30天和启用起点；时区；审批恢复只算一 run；工具复用；unknown token；旧版本409；重置后不重生；保留源记录；真实装配与预览一致。
- 真实 PG：V10 Flyway 正常执行；合成用户启用→行为统计→偏好预览/装配→重置→再启用，报告只含合成ID与断言。
- 控制台真实浏览器：查询、启用、时区修改、偏好预览、重置；页面无 JS 错误。模型及 embedding 用 mock，不进行付费抽取。
- 全量 `mvn test`；存量记忆、长期记忆、路由和审批回归。真实付费抽取和真实 embedding 校准未重跑时明确记为未执行，不能用 mock 代替。

本轮验收：全量 606 条、0 失败、0 错误、1 跳过；真实画像 23/23，短期记忆 20/20、审批 4/4、长期记忆手工/召回 21/21，控制台浏览器手验通过。详细命令、报告和覆盖边界见 [m4-3b-validation.md](m4-3b-validation.md)。
