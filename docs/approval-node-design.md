# 人工审批节点：挂起 / 恢复设计（2026-09-22 定稿，实现中）

> M4-3A 已批准实施：最终决策和 RESUME 持久任务同一事务提交；挂起完成当前任务并释放租约，恢复新任务绑定 checkpointId 去重。
> 保留不重放、工作流版本校验和挂起不持锁三条语义；具体契约见 `m4-3-design.md` §3 与 architecture §17。

> 本文是实现契约。范围 = M5 的「人工审批节点（超时/升级）与通知」，提前到 M3 收尾阶段做。
> 状态：**第一版已交付（2026-09-22）** —— P1（挂起引擎 + 审批节点 + 决策接口 + 超时）与 P2（升级、待办列表、取消、端到端）均已完成；
> V6 迁移（3 张新表 + `workflow_runs` 加一列）经用户批准并已在真实 PG 上应用。验证：`ExecutionEngineSuspensionTest` 6 +
> `ApprovalNodeExecutorTest` 7 + `ApprovalFlowIntegrationTest` 6 全绿；真实栈脚本 `script/verify-approval-flow.ps1` 4 项检查全过
> （记录在 `docs/quality-plan.md` §6）。§10「明确不做」与 §11「已知边界」是这一版的实际边界。

## 1. 要解决什么

工作流跑到某一步需要**人**来决定继续还是停：退款要财务点头、删除数据要负责人点头、对外发文要审核。
在引擎里这等于一个全新语义 —— **运行需要挂起等待外部输入，然后从断点继续**。

现有执行模型决定了这个语义必须**新增在引擎层**，不能用节点内部阻塞糊过去：

| 现状 | 事实 | 对审批的含义 |
| --- | --- | --- |
| `ExecutionEngine.execute()` 同步一把梭（拓扑序 for 循环） | 没有挂起/恢复概念 | 要在引擎里加「挂起结果」与「带初始状态进入」 |
| 节点执行走 `nodeExecutorService` + `executeWithTimeout`（默认 60s） | 超时即掐 | 审批**不能**靠线程等待，否则等人 1 小时必被掐 |
| `SessionDispatcher` 按 sessionId 串行 + `SessionLock` | `RunCoordinator.process` 整段运行持锁 | 挂起必须**释放**锁，恢复时**重新取锁** |
| `node_runs` 记 attempt / status / input / output | 没有「等待中」状态 | 新增 `NodeRunStatus.WAITING` |

## 2. 方案：挂起 + 检查点恢复

不做阻塞式等待（占线程 + 占会话锁 + 重启即丢 + 跨实例不可用）。做「**落检查点 → 释放 → 决策 → 从断点继续**」：

```text
execute()                             resume()
   │                                     ▲
   ├─ 节点执行 ──► NodeResult.suspend ──┐ │
   │                                   │ │
   │  ① node_runs 行 -> WAITING（ended_at 留空）      │
   │  ② 写 run_checkpoints（variables/activated/下标/步数/版本）
   │  ③ workflow_runs -> WAITING_APPROVAL + waiting_node_id
   │  ④ 事件 NODE_SUSPENDED / RUN_WAITING / APPROVAL_REQUESTED
   └───────────────────────────────────┘
   RunCoordinator 释放 session 锁，worker 立刻回收
                                         │
   决策（人 / 超时 / 取消）──► CAS 单赢家 ─┘
   重新入队（SessionDispatcher）→ 重取 session 锁 → engine.resume
```

三条**不可妥协**的语义（都有测试钉住）：

1. **不重放已执行节点**。恢复从挂起节点的**下一个拓扑位**继续；挂起节点自身不再执行。否则 `tool.call` 会重复出网、`llm.chat` 会重复扣费。
2. **恢复前校验工作流版本**。检查点记 `workflowVersion`，与运行绑定的定义不一致就**拒绝恢复**并给明确错误（不是静默换版跑完）。
3. **挂起期间不持会话锁**。同会话后续消息可以正常跑完。这是**有意**的取舍（锁住会话等于把人不在的时间也算进会话阻塞），会写进文档与用例，不当 bug 藏。

### 检查点内容

```json
{
  "nodeId": "approve",
  "nodeRunId": "uuid",
  "nextIndex": 3,
  "activated": ["start", "approve", "end"],
  "variables": { "input": "……", "amount": "1000" },
  "steps": 2,
  "workflowVersion": 3
}
```

- `variables` 是**挂起那一刻**的变量快照（不含审批节点自己的输出）；
- `activated` 是当时的激活集合，恢复后先按决策变量求值挂起节点的出边、再并入；
- `steps` 带过去，步数预算**跨挂起累计**（否则「审批一次重置一次预算」就是白送的绕过口）；
- 恢复时把审批输出注入变量：`approvalStatus` / `approvalBy` / `approvalComment` / `approvalLevel` / `approvalWaitedMs`，
  下游可以据此走条件边（SpEL 变量引用，条件表达式写法与现有 `EdgeDef.when` 一致）。

## 3. 状态机与事件

```text
RunStatus:    PENDING -> RUNNING -> WAITING_APPROVAL -> RUNNING -> SUCCEEDED / DEGRADED / FAILED
                                                        └─(取消)──> CANCELED
NodeRunStatus: RUNNING -> WAITING -> SUCCEEDED / DEGRADED / FAILED
```

取消挂起中的运行（`POST /api/v1/runs/{id}/cancel?actor=`）：运行置 `CANCELED`、该运行所有 PENDING 待办置 `CANCELED`、
检查点消费掉、**断点节点行收口为 FAILED + 「审批等待期间被取消」** —— 与「运行中取消」同一口径（那里也是把在跑的节点行收成
FAILED + 取消原因），不留一个永远挂着 WAITING 的节点行。

新事件（`docs/architecture.md` §5/§6 同步）：

| 事件 | 发出方 | 载荷 |
| --- | --- | --- |
| `NODE_SUSPENDED` | 引擎 | nodeId / type / approvalId |
| `RUN_WAITING` | 引擎 | nodeId / kind=approval / dueAt |
| `APPROVAL_REQUESTED` | 审批服务 | approvalId / nodeId / level / title / assignees / dueAt |
| `APPROVAL_DECIDED` | 审批服务 | approvalId / decision / decidedBy（开鉴权时为令牌主体 id，否则自报）/ comment / waitedMs |
| `APPROVAL_ESCALATED` | 审批服务 | approvalId / fromLevel / toLevel / assignees |
| `APPROVAL_EXPIRED` | 审批服务 | approvalId / level / action（approve/reject/escalate） |

## 4. 接口契约

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/approvals?status=PENDING&assignee=&limit=` | 待办列表（直接查库，多实例安全） |
| POST | `/api/v1/approvals/{approvalId}/decision` | body：`decision=APPROVE\|REJECT`、`decidedBy`、`comment` |
| GET | `/api/v1/runs/{runId}/approvals` | 运行视角的审批历史 |
| GET | `/api/v1/approvals/notify` | 通知渠道状态：渠道清单 / 队列水位 / 已发、失败、去重、丢弃计数（见 §12） |
| POST | `/api/v1/runs/{runId}/cancel` | 运行中 = 原路径；挂起中 = 置 CANCELED + 待办置 CANCELED |

用 `approvalId` 而不是 `runId+nodeId` 做决策地址：升级后 `run+node` 不再唯一。

控制台第 4 节「人工审批」是这套接口的看板：待办列表（含到期倒计时）、批准 / 拒绝（顶部可填「令牌」，
鉴权开启时以令牌身份为准、`decidedBy` 不用也不该手输；未开启时才手输，默认与 assignee 同值）、
单条审批的事件流、按 `runId` 查审批链、通知渠道与队列计数。顶部还有「我是谁」（`GET /api/v1/auth/whoami`，
匿名也 200）：排障第一问就是「服务端到底认没认出我」。**没有真实签发方时不放假登录框** ——
只有静态令牌输入（P3-3，见 `docs/auth-design.md`）。

错误口径：`decidedBy` 为空、`decision` 非法（非 APPROVE/REJECT）、`status` 非 PENDING → 400 带可读原因；
非法 JSON 请求体 → 400，**响应与日志都不回显报文内容**（Jackson 原始 message 会带请求体前几十个字符）；重复决策 → 409。

决策人校验：必须是 `assignees` 之一，或命中 `aiflow.approval.admin-users`；`assignees` 含 `any` 表示不限。
**它是不是安全边界，取决于鉴权开关**（P3-3 已落地，见 `docs/auth-design.md`）：
开了 `aiflow.auth.enabled` 时 `decidedBy` 来自可验证令牌（`@RequireIdentity` 拦无身份的请求，自报与令牌不一致直接 400），
名单校验这时才是权限控制；**关着（默认）时它仍是自报字段** —— 谁能传对 `decidedBy` 谁就能批，只有防误操作的价值。
部署到底开没开，问 `GET /api/v1/auth/whoami`（`data.enabled`）。

## 5. 表结构（V6，已批准）

```sql
create table approvals (
    id uuid primary key,
    run_id uuid not null references workflow_runs (id),
    node_id varchar(64) not null,
    level integer not null default 1,
    status varchar(24) not null,          -- PENDING / APPROVED / REJECTED / EXPIRED / CANCELED
    title text, detail text,
    assignees varchar(512) not null,
    policy jsonb not null,                -- 请求时刻的策略快照（超时/升级/拒绝语义）
    decision varchar(24), decided_by varchar(128), decided_at timestamptz, comment text,
    due_at timestamptz not null,
    created_at timestamptz not null, updated_at timestamptz not null
);
create unique index uq_approvals_level on approvals (run_id, node_id, level);

create table approval_events (            -- 追加型：谁、何时、做了什么
    id uuid primary key,
    approval_id uuid not null references approvals (id) on delete cascade,
    event varchar(32) not null, actor varchar(128), comment text, detail jsonb,
    created_at timestamptz not null
);
create index idx_approval_events_approval on approval_events (approval_id, created_at);

create table run_checkpoints (
    id uuid primary key,
    run_id uuid not null references workflow_runs (id) on delete cascade,
    node_id varchar(64) not null, seq integer not null,
    state jsonb not null,
    created_at timestamptz not null, consumed_at timestamptz
);
create index idx_run_checkpoints_run on run_checkpoints (run_id, seq);

alter table workflow_runs add column waiting_node_id varchar(64);
```

**为什么策略快照进审批行**（`policy`）：超时/升级要读节点的 `onTimeout` / `maxLevel` / 升级人选，
而读节点参数就得走 `WorkflowService -> GraphValidator -> NodeRegistry -> 执行器` —— 那里又依赖审批服务，
会绕成环。更重要的是**语义**：审批一旦发出，规则就该冻结在请求时刻，改工作流不该改变一个**已经在等**的审批怎么超时。

**为什么单独 `approval_events`**：审批是全系统最该留痕的动作，而现有 `tool_audit_events` 只记工具调用。
两行代码的追加表换「谁批的、谁升级的、谁让它过期的」可查，值。

## 6. 超时与升级

- 决策窗口用节点 param `waitSeconds`（默认 `aiflow.approval.default-wait-seconds` = 3600），**不复用** `timeoutSeconds`
  —— 后者是引擎的节点执行超时（默认 60s），语义被重定义会把「等人」误判成「节点超时」。
- `onTimeout: approve | reject | escalate`（默认 `reject`）；`escalate` 建 `level+1` 行并按 `escalateAssignees` 重排期，
  到 `maxLevel` 后按 `onExhausted`（默认 `reject`）收尾。
- 扫描：`@Scheduled`（`aiflow.approval.sweep-ms`，默认 5000）跑 `status='PENDING' and due_at<=now()`，
  用**条件更新**抢占（受影响行数 = 1 才处理）→ 多实例天然幂等，不需要分布式锁。
- 拒绝语义：默认 `onReject=fallback` → 节点 DEGRADED + 走兜底话术（**拒绝是正常业务结果，不是故障**）；
  `onReject=fail` 才是 FAILED。两者都测。

## 7. 幂等与并发

| 场景 | 机制 |
| --- | --- |
| 同一审批被批两次 | `update approvals set status=... where id=? and status='PENDING'`，0 行 → 409 + 当前状态 |
| 人工决策与超时扫描同时到 | 同一条件更新，只有一个赢家 |
| 恢复被触发两次 | `update workflow_runs set status='RUNNING' where id=? and status='WAITING_APPROVAL'` CAS |
| 审批节点重试导致重复待办 | 校验期**禁止** `retry` 配在审批节点上（唯一索引 `(run_id,node_id,level)` 是第二道闸） |
| 多实例 | 决策 / 恢复 / 超时全走 DB 条件更新；恢复再经该实例的 `SessionDispatcher` + Redis 会话锁 |

## 8. 实现分解

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 引擎 | `run/ExecutionEngine` | 循环体抽成「可带初始状态进入」；`execute()` / `resume()` 共用 |
| 引擎 | `run/RunCheckpoint`、`run/CheckpointStore` | 检查点领域模型 + 存取接口（JPA 实现在 `persistence`） |
| 引擎 | `run/RunRecorder`(+JPA) | `claimResume` / `markWaiting` / `suspendNodeRun` |
| 引擎 | `node/NodeResult`、`NodeRunStatus`、`NodeExecutor.validate` | 挂起结果 + 装配期参数校验扩展点 |
| 审批 | `approval/ApprovalService` | 状态机 + 持久化（**不依赖引擎**，避免环） |
| 审批 | `approval/ApprovalResumeService` | 决策后编排：映射成节点结果 + 重新入队恢复 |
| 审批 | `approval/ApprovalTimeoutScheduler` | 超时/升级扫描 |
| 审批 | `node/builtin/ApprovalNodeExecutor` | `type=approval`，落待办后返回挂起 |
| API | `api/ApprovalController`、`RunController.cancel` | 待办 / 决策 / 取消 |

依赖方向（防环）：`ApprovalNodeExecutor -> ApprovalService`（不含引擎）；
`ApprovalResumeService -> ApprovalService + RunCoordinator -> ExecutionEngine`。

## 9. 测试清单

1. 挂起：run `WAITING_APPROVAL`、节点行 `WAITING` 且 `ended_at` 为空、检查点落库、事件序列正确；
2. 批准恢复：后续节点执行、run `SUCCEEDED`、`approvalStatus` 等变量可见；
3. **不重放**：挂起前的节点只执行一次（计数器钉住）；
4. 拒绝：`fallback`（DEGRADED + 话术）与 `fail`（FAILED）两条路；
5. 步数预算跨挂起累计（`maxSteps` 小的引擎上挂起一次后仍能触发超限）；
6. 重复决策 409；并发决策只有一个赢；决策已终态运行 → 冲突；
7. 超时三条策略（approve / reject / escalate）+ 升级到顶走 `onExhausted`；
8. 挂起期间工作流改版 → 恢复被拒并给明确错误；
9. 挂起期间取消 → run `CANCELED`、待办 `CANCELED`、不会恢复；
10. 端到端脚本：真实引擎 + 真 PG，走「提交 → 挂起 → 待办 → 决策 → 恢复 → 终态」，并验证挂起期间同会话的另一条消息能先跑完；
11. 通知：REQUESTED / DECIDED / CANCELED / EXPIRED 四类都真的投到接收方，签名可被独立验算；
    投递失败只计数、队列满丢最老、同一状态变化只发一次（分发器单测）；「配了没生效」在状态接口里看得见。

## 10. 明确不做（写在这里，避免以后当遗漏）

- 审批人在页面**改参数**再继续（那是「人工节点」，另一个语义）：第一版只有批准 / 拒绝 + 备注。
- 审批模板、审批人组织架构、代理/委托：属于另一套系统。
- 邮件 / IM 通知：要真实口令与第三方 SDK，收益不抵复杂度。已交付的 webhook 覆盖了「审批人不在页面上」的场景；
  要接新渠道就是实现 `ApprovalNotifier` 一个类，Dispatcher 自动发现，不改状态机。
- 跨实例审批锁：DB 条件更新已经够，不加分布式锁。

## 12. 通知适配器：log + webhook（2026-09-22 交付）

状态机的**每一次转移发一条**通知，事件名取转移之后的语义：

| 事件 | 何时发 | `level` 的含义 |
| --- | --- | --- |
| `REQUESTED` | 落了新待办（升级产生的新层也算） | 这张审批单自己的层 |
| `DECIDED` | 收口为 APPROVED / REJECTED（人工或超时策略） | 同上 |
| `EXPIRED` | 超时升级：旧层过期、新层还没人批（**非终局**） | **升级后的目标层**，`fromLevel` 才是超时那层 |
| `CANCELED` | 运行被取消，待办作废 | 这张审批单自己的层 |

没有单独的 `ESCALATED`：升级在库里本来就是两行（旧层 EXPIRED + 新层 PENDING），再发一条「升级了」
会让一次超时变成三条卡片；`EXPIRED(level=2, fromLevel=1)` 加新层的 `REQUESTED` 已经把两件事说全。

四个决定：

- **异步**：`ApprovalNotifyDispatcher` = 单线程 + 有界队列（`queue-size`，满则丢**最老**并计数）。
  决策路径上只有一次入队 —— webhook 超时是秒级的事，不能挂在审批决策上。
- **只在提交后投递**：`ApprovalService` 在事务里注册 afterCommit 回调才入队。事务回滚了就不该有通知，
  否则接收方会收到一条「从来没发生过的待办」，而且它永远不会被撤销。
- **去重**：`(approvalId, event, level)` 的进程内窗口（`dedupe-size`）：重放、重扫不会给接收方灌重复卡片。
- **失败只计数**：不重试、不回滚审批；`sent` / `failed` / `dropped` / `lastError` 都暴露在
  `GET /api/v1/approvals/notify` 上。要可靠投递请查审批链（`approvals` / `approval_events` 是持久的），
  按 `dedupeKey` 拉取比让引擎扛重试更简单。

渠道：

- `log`（默认开）：写进 logger `aiflow.approval.notify`，给排障对时间线用，标题截 200 字。
- `webhook`（默认关）：POST JSON。**出网走与 HTTP 工具同一份护栏**（`SsrfGuard`：协议 / 端口 /
  host 白名单 / 内网地址解析），否则审批节点就成了「往任意地址发 POST」的开放代理 —— 而审批节点常由业务方
  在低代码页面上自己拖出来，攻击面比工具调用更大。URL 过不了护栏时渠道标记为未就绪（原因写在状态接口里），
  **不影响审批本身**；每次投递还会重新校验一遍地址（解析结果会变）。

载荷：自解释的 JSON（`version` / `event` / `dedupeKey` / `approvalId` / `runId` / `nodeId` / `level` /
`fromLevel` / `status` / `title` / `detail` / `assignees` / `dueAt` / `decidedBy` / `comment` /
`decidedAt` / `at`），字段是**事发那一刻的快照**，接收方不用回查引擎就能渲染一张待办卡片。
header 带 `X-AIFlow-Event` 与 `X-AIFlow-Approval-Id`（路由用），签名可选：
`X-AIFlow-Signature: sha256=hex(HMAC-SHA256(secret, body))`，secret 只从
`AIFLOW_APPROVAL_WEBHOOK_SECRET` 注入，不进库、不进日志。

配置（`aiflow.approval.notify.*`）：`enabled`（总开关）、`queue-size`、`dedupe-size`、
`webhook.{enabled,url,timeout-ms,secret}`。

## 11. 已知边界

- SSE 默认 30 分钟超时（`aiflow.engine.sse-timeout-ms`），等待超过它客户端会断连；`Last-Event-ID` 重连可补事件。
  事件仍是**进程内**环形缓冲（默认 2000 条 / 运行），跨进程重启丢回放 —— 决策与运行状态以 DB 为准。
- `node_runs.ended_at` 在恢复时才写，因此该行「耗时」含等待时间；真实等待时长在
  `node_runs.output.approvalWaitedMs`（要算节点自身开销就用后者减）。
- 运行时长口径：`workflow_runs.started_at` 是**首次**开始时间（挂起不重置），等待时间计入总时长。当前在等什么看两处：
  `workflow_runs.waiting_node_id`（等哪个节点）与 `GET /api/v1/runs/{runId}/approvals`（该层审批的 `createdAt` / `dueAt`）。
