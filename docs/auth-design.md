# 鉴权最小切片：从自报身份到可验证身份（P3-3，2026-09-22 落地）

> 状态：**已实现**（2026-09-22，用户批准后落地；实测数字见 §8）。
> 本文档是契约：实现与它不一致时，先改这里再改代码。

## 1. 要解决什么

现状：`POST /api/v1/approvals/{id}/decision` 的 `decidedBy`、`POST /api/v1/runs/{runId}/cancel` 的 `actor` 都是**调用方自报的字符串**。
于是 `ApprovalService.canDecide` 的名单校验变成「谁说自己是 `reviewer-a` 谁就能批」—— 防误操作可以，防人不行。

这不是新问题，文档里已经反复写明（README「已知边界」、`docs/approval-node-design.md` §4、AGENTS.md），
本切片就是把这条口径兑现：**让这两个字段来自可验证的凭证**，同时**不破坏本地零配置开发**。

## 2. 方案（一句话）

静态 Bearer 令牌 -> 主体（subject）；`@RequireIdentity` 声明式要求身份；身份在场时**覆盖自报字段**；**默认关闭**。

### 2.1 为什么是静态令牌，不是 JWT / Spring Security

- 现在**没有用户体系、没有签发方**，也没有「令牌要过期、要按用户签发、要多实例共享密钥」的需求。JWT 的签名 / 过期 / 刷新三件事当前无处发力，
  引入 `jjwt` 只是把一串常量换个地方放。
- 引入 `spring-boot-starter-security` 的代价是立刻多出一套过滤器链与配置模型（"为什么这个接口 401、那个 403"），
  而本切片只有**两个**权限点。与「不为一个注解引入一套配置模型」是同一口径（见 `docs/roadmap.md` 的熔断取舍）。
- 令牌的保管方式沿用 MCP 的 `auth-token-env` 纪律：**值只来自环境变量，yml 里只有变量名**。
- **什么时候升级成 JWT**：出现「令牌要过期 / 要按用户签发 / 要跨实例共享密钥」中的任意一条。届时换的是解析器，`Subject` 与业务侧的取值方式不变。

## 3. 契约

### 3.1 配置

```yaml
aiflow:
  auth:
    enabled: ${AIFLOW_AUTH_ENABLED:false}
    subjects:
      - id: reviewer-a
        display-name: 审批人 A
        token-env: AIFLOW_AUTH_TOKEN_REVIEWER_A   # 值只来自环境变量 / JVM 系统属性，yml 里只有变量名
      - id: admin
        display-name: 管理员
        token-env: AIFLOW_AUTH_TOKEN_ADMIN
```

| 字段 | 含义 | 默认 |
| --- | --- | --- |
| `enabled` | 总开关 | `false` |
| `subjects[].id` | 主体 id，会写进 `decidedBy` / `actor` 与审计 | 必填 |
| `subjects[].display-name` | 给人看的名字（`/whoami`、控制台） | 可空，回落到 id |
| `subjects[].token-env` | **环境变量名**，值才是令牌 | 必填 |

令牌取值顺序：**环境变量优先，取不到时读 Spring `Environment` 的同名属性**（覆盖 `-D` 系统属性与
`@DynamicPropertySource` 注入 —— 生产只设环境变量，测试走这条注入口）。两条路共用同一份解析代码，
否则「测试通过」与「线上能跑」是两件事。**比较严格**：大小写与前后空格都不宽容（宽容就是给爆破留缝），
传输层的空白由 `IdentityFilter` 处理。

装配期校验（不静默，与工具白名单同一风格）：

- `enabled=true` 但 `subjects` 为空 -> **启动失败**（会得到一个"谁都不认识"的服务）
- 任一 `token-env` 取不到值 -> **启动失败**，只列变量名，**不打值**
- 两个主体的令牌相同 -> 启动失败（谁是谁说不清）
- `enabled=false` -> 启动打一条 WARN：「鉴权关闭：decidedBy / actor 仍是自报字段，审批人名单不是安全边界」

### 3.2 请求怎么带身份

- `Authorization: Bearer <token>`
- 覆盖范围：`/api/**` 与 `/actuator/**`，**`/actuator/health` 例外**（编排探活要免鉴权）。
  静态资源（`console.html` 等）不拦 —— 页面本身不含数据，数据都在 `/api` 后面。
- 令牌无效 / 未知 -> **401，立刻失败，不降级为匿名**：「你没带令牌」和「你的令牌不认」必须分得开。
- 没带令牌 -> 匿名继续；要不要 401 由接口上的 `@RequireIdentity` 决定。

### 3.3 错误口径（一次说清）

| 场景 | 结果 |
| --- | --- |
| 无令牌访问 `@RequireIdentity` 接口 | **401** `40100` 未认证 |
| 令牌未知 / 格式不对 | **401** `40100`（不降级为匿名） |
| 令牌有效但不在审批人名单 | **403** `40300`（沿用现有文案与语义） |
| 令牌有效，但自报字段与令牌身份不一致 | **400** `40000`「已开启鉴权：xxx 由令牌身份决定，不要再自报」 |
| 鉴权关闭 | 一切照旧（自报字段生效），启动 WARN 已明说这不是安全边界；`@RequireIdentity` **不生效** |

本切片标 `@RequireIdentity` 的接口**只有两个**（其余不动，原因见 §7）：

- `POST /api/v1/approvals/{approvalId}/decision`
- `POST /api/v1/runs/{runId}/cancel`

新增 `GET /api/v1/auth/whoami` -> `{enabled, authenticated, subject:{id, displayName}}`。
匿名也返回 **200**（`authenticated=false`）—— 它是给控制台和排障用的，故意不 401。
`ErrorCode` 新增 `UNAUTHORIZED(40100, 401, "未认证")`。

## 4. 身份怎么流到业务

1. `IdentityFilter`（`OncePerRequestFilter`，排在 `TraceIdFilter` 之后）：解析令牌 -> `IdentityContext.set(subject)`，
   `finally` 里**必须清理**（Tomcat 线程复用，不清理就是串身份）。
2. `RequireIdentityInterceptor`：**先看总开关** —— `aiflow.auth.enabled=false` 时 `@RequireIdentity` 不生效
   （注解说的是「这个接口需要身份」，「要不要身份」由部署开关决定；否则本地零配置开发会被自己的注解拦死，
   而且错误提示是「请带令牌」，与「服务端根本没开鉴权」南辕北辙）。开启时：handler（方法或类）带注解且
   `IdentityContext.current() == null` -> 401，`preHandle` 抛出的异常同样走 `@RestControllerAdvice`，错误包装与其它接口一致。
3. 业务侧：`ApprovalController.decide` / `RunController.cancel` 读 `IdentityContext.current()`——
   有身份就用身份；自报字段与身份冲突则 400（§3.3）。

**为什么不用 `@PreAuthorize`**：本切片只有「有身份 / 没身份」两种状态，方法级表达式 + `SecurityContext` 是净负担；
等出现角色权限（第二条规则）再评估。

## 5. 实现分解

新增：

| 文件 | 职责 |
| --- | --- |
| `common/auth/Subject.java` | record：`id`、`displayName` |
| `common/auth/AuthProperties.java` | `aiflow.auth.*` 绑定 |
| `common/auth/SubjectRegistry.java` | 装配期校验 + 令牌解析（`MessageDigest.isEqual` 常量时间比较） |
| `common/auth/IdentityContext.java` | ThreadLocal 持有与清理 + 自报字段的覆盖策略（`resolveActor`） |
| `common/auth/IdentityFilter.java` | 解析 `Authorization`，无效即 401，匿名放行 |
| `common/auth/RequireIdentity.java` + `RequireIdentityInterceptor.java` | 声明式要求身份 |
| `api/AuthController.java` | `GET /api/v1/auth/whoami` |

改动：`common/api/ErrorCode.java`（+401）、`config/EngineConfig.java`（注册属性与拦截器）、
`api/ApprovalController.java`、`api/RunController.java`、`resources/application.yml`（`aiflow.auth` 段）、
`resources/static/console.html`（令牌输入 + whoami 显示；填了令牌时审批不再需要手输 decidedBy）、README / roadmap / AGENTS。

无数据库变更（不需要迁移）。

## 6. 测试清单

单元 `SubjectRegistryTest`（10 条，不依赖 Spring）：令牌命中 -> 主体、displayName 回落、**比较不宽容**
（大小写 / 前后空格 / 前缀）、`enabled=false` 时什么都不读、空名单 + 关闭可容忍，
以及四条启动校验（空名单 / 变量缺失（只报变量名） / 令牌重复 / id 重复）。

单元 `IdentityContextTest`（4 条）：这是**兼容性承诺** —— 没有身份时自报字段照旧生效；
有身份时身份优先（自报为空也用身份）；自报冲突 -> 400 而不是静默忽略；清理后身份消失。

集成 `AuthIntegrationTest`（9 条，H2，本上下文鉴权开着）：`/whoami` 匿名与带令牌两态；
无令牌 -> 401 且审批仍 PENDING；坏令牌 -> 401（**不是**匿名放行）；非名单令牌 -> 403 且**不消耗**审批；
自报冲突 -> 400 且不消耗；令牌身份 + body 省略 `decidedBy` -> 200 且落库 `decided_by` = 主体 id；
`cancel` 无令牌 -> 401、带令牌 -> 409（证明过闸）；`actor` 自报冲突 -> 400。

集成 `AuthDisabledIntegrationTest`（4 条，H2，`aiflow.auth.enabled=false` 的独立上下文）：**这是补的课** ——
第一版把 `@RequireIdentity` 写成静态注解，关闭态仍然拦人，12 条存量用例集体 401。所以「关着时整个切片不生效」
现在有**显式**断言，不再靠「默认上下文恰好没开鉴权」隐式覆盖：`/whoami` 如实报 `enabled=false`；
不带令牌 + 自报 `decidedBy` -> 200 且落库就是自报的人；自报的人不在名单 -> 仍然 403 且**不消耗**审批
（业务层「谁能批」不因鉴权关闭而消失）；带个乱令牌也被忽略（「关」= 整个切片不参与，不是「半开」）。

另有既有的 `ApprovalFlowIntegrationTest` / `ApprovalNotifyIntegrationTest` / `ErrorResponseIntegrationTest`
（默认上下文）继续证明鉴权关闭时全链路无回归。

## 7. 明确不做（写在这里，避免以后当遗漏）

- 不做用户注册 / 登录页 / 密码存储（没有用户体系，凭空造一个只会更不安全）
- 不做 JWT 签发与刷新（见 §2.1 的升级条件）
- 不做角色 / 权限矩阵（当前只有一处权限判断，加了也只有一处用；出现第二个权限点时再设计）
- 不做多租户与数据隔离
- 不做限流（额度已按 userId 计量，见 `aiflow.budget`）
- **不给 `/api/v1/**` 全量加身份**：本地开发与控制台要零配置可用，审批决策是当前唯一「身份即边界」的点
- 不碰 `.env`（仓库里也没有这个文件）、不把令牌写进 yml / 代码 / 日志 / 报告

## 8. 验证方式（批准后执行）

- 令牌只存在于启动进程的环境变量里；报告与脚本输出只记主体 id 和变量名，不记录值或前缀（2026-10-09 收紧验证纪律）。
- 新增 `script/verify-auth-flow.ps1`（真实栈）：无令牌 401 / 坏令牌 401 / 非名单 403 / 自报冲突 400 /
  正确身份 200 且落库 `decidedBy` = 主体 id / `/whoami` 两态；控制台填令牌走一遍批准。
- 回归：鉴权关闭时 `script/verify-approval-flow.ps1` 仍 PASS；全量 `mvn test` 0 失败。

### 8.1 实测（2026-09-22，全部已执行）

| 验证 | 结果 |
| --- | --- |
| 单元 `SubjectRegistryTest` + `IdentityContextTest` | 14 条绿 |
| `AuthIntegrationTest`（开） | 9 条绿 |
| `AuthDisabledIntegrationTest`（关） | 4 条绿 |
| 全量 `mvn test` | **436 条，0 失败，1 跳过，BUILD SUCCESS** |
| `script/verify-auth-flow.ps1`（真实栈：8081 鉴权开 + PG/Redis） | **PASS 10/10**；令牌身份落库 `decidedBy=reviewer-a` |
| 鉴权关闭回归 `script/verify-approval-flow.ps1 -BaseUrl http://localhost:8080` | PASS |
| 控制台（浏览器，8081） | 匿名点批准 -> `40100 该接口需要身份`；填令牌 ->「已认证：reviewer-a」-> 批准成功、待办 0、链上决策人 `reviewer-a` |

令牌值只在启动进程的环境变量里（`e2e-*` 一次性串），报告与脚本输出只出现主体 id 与令牌前缀，不落全文。

## 9. 需要批准的事项（红线：新增令牌环境变量）——**2026-09-22 已批准并执行**

1. 新增三个环境变量（值由你定或我用一次性随机串，只在你本机进程里）：
   `AIFLOW_AUTH_ENABLED`、`AIFLOW_AUTH_TOKEN_REVIEWER_A`、`AIFLOW_AUTH_TOKEN_ADMIN`；
2. 默认值保持 `enabled=false`（本地零配置可用，要真边界时显式打开）；
3. `ErrorCode` 新增 401 码（契约变更，本文档先写）。
