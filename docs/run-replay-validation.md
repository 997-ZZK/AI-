# 运行回放与失败定位验收

状态：**2026-10-14 已实现并完成本机验收**。定向 30/30，全量 998 条、0 失败、0 错误、6 条沿用开关跳过；真实回放 API 23/23、原演示 43/43、浏览器 22 项通过。方案与接口契约见 `run-replay-plan.md`。

## 交付范围

- 新增 `GET /api/v1/runs/{runId}/nodes?page=0&size=50`，返回每次节点尝试的摘要。数据库字段投影不读取 input/output/error，使用 Slice 限制每页最多 200 条；空 startedAt 排末尾，再按时间与唯一 ID 稳定排序。
- 新增 `GET /api/v1/runs/{runId}/nodes/{nodeRunId}`，联合所属 runId 查询单条快照。跨运行或不存在的节点返回 404，分页非法值和偏移溢出返回 400。
- 控制台 3.2 面板支持 runId、整体状态、节点分页、手动刷新、按需详情。切换输入、页码和刷新使旧请求失效，业务文本通过 textContent 渲染。

查看的是 PostgreSQL 已保存的执行事实，不会重新执行工作流；Redis 事件的 2000 条/24 小时保留期不决定节点快照能否查看。没有快照的未执行节点不生成虚构行。失败行表示某一次尝试，整体结果由运行状态决定；耗时包含审批等待，未结束或时间不完整时为 null。

## 验证方式

使用 JDK 21、Maven 3.9.16 与既有本机缓存，所有 Maven 命令使用 `-o -s target/m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository'`：

1. `'-Dtest=RunReplayIntegrationTest,ErrorResponseIntegrationTest' test`。
2. `'-DskipTests=false' test`。
3. `'-DskipTests' package`；独立包为 `target/ops/replay-20261014-engine.jar`。

新查询集成测试覆盖 SQL 投影/数据库分页、稳定顺序与空时间、默认及最大页大小、重试分行、空运行/空页、详情与归属、4xx、耗时和 GET 不写入。原整体运行接口同时回归。

真实栈通过统一 offline-engine 启动器固定 mock，复用既有 V10 PostgreSQL 和 Redis。合成 runner 仅通过正常 API 新增 14 节点成功、三次重试失败、降级、审批等待/批准/拒绝/取消的记录，随后只发 GET 验证查询契约。报告仅存检查名、合成 ID、状态、计数；旧产物与业务记录保留。

真实浏览器验收分页、详情、刷新、错误 runId、安全文本渲染和快速切换，回归对话 SSE 及审批待办/批准/拒绝/事件。受控延迟代理仅在 127.0.0.1:8084 转发本机 8083 的 GET，用真实响应延迟验证请求切换，不在页面注入替代实现。

## 验收结果

| 检查 | 结果 | 证据（`target/ops/`） |
| --- | --- | --- |
| 定向回归 | 30/30：回放 23 条、错误响应 7 条，0 失败/错误 | `replay-20261014-targeted-retry04.log` |
| 全量离线回归 | 998 条，0 失败、0 错误、6 跳过 | `replay-20261014-tests-retry01.log`、当前 Surefire XML 统计 |
| 离线打包 | BUILD SUCCESS，独立 JAR 已保留 | `replay-20261014-package.log`、`replay-20261014-engine.jar` |
| 原端到端演示 | 43/43，含对话/SSE、记忆、RAG、音频、审批和取消 | `replay-20261014-demo-report.json` |
| 真实 PG 回放 API | 23/23，含摘要字段白名单、分页、空页、详情归属、400/404与状态 | `replay-20261014-api-report.json` |
| 合成运行 | 4 个工作流、7 次运行；14 节点成功分为 10/4 两页，失败节点保留 3 次尝试 | `replay-20261014-scenarios.json` |
| 真实浏览器 | 22 项通过；按需快照、安全文本、错误提示、SSE、审批和切换检查 | `replay-20261014-browser-report.json`、`replay-20261014-browser-*.jpg` |
| 受控延迟 | 列表/详情各延迟 2 秒，各实际取消 1 次；延迟完成后当前运行/页面未被覆盖，上游/内部错误均 0 | `replay-20261014-delay-counts-retry01.json` |
| 停止与复查 | 自建引擎 STOPPED，8083/8084 无监听，共享 PG/Redis 仍健康 | `replay-20261014-stop.log`、`replay-20261014-stopped-status.log`、`replay-20261014-lifecycle-report.json` |

独立 JAR SHA256：`7b21e1ab6dd456bd082e857098fdc0571366ec5f9275edbb9402cfb727162697`。此前第二层独立包保留。本轮运行期间没有替换独立 JAR。

浏览器检查确认：失败行分别显示 attempt 1/2/3；降级运行没有被其中一次失败误标为整体失败；等待审批显示“未结束”，批准后同一审批节点保持 attempt=1，耗时包括约 285 秒人工等待。取消运行显示整体 CANCELED，审批节点按既有收口语义显示 FAILED。合成 `<img ... onerror=...>` 在输入详情中原样显示为文本，没有生成 img DOM。最终延迟验收页面的 JavaScript error 日志为空。

准备与 API 报告记录的是各自验收时点；浏览器随后批准了报告中的 waitingRunId，并新增两次独有前缀的对话/拒绝运行，不应将历史报告中的 WAITING_APPROVAL 当作当前状态。首轮延迟检查的列表已在切换前返回，因此保留首轮材料，并重新启动仅本机代理；最终 `retry01` 报告明确记录两次取消，作为请求未完成时切换的证据。

## 实施中发现

定向测试初版错误地要求第一页 SQL 必须带 `offset 0`，而 Hibernate 正确省略零偏移。现分别请求第一页与第二页，验证摘要列、每页限制与非零偏移，未放宽生产契约。最终定向 30/30 通过（新增回放 23 条、错误响应 7 条）。

沙箱首次编译曾拒绝读取既有 Maven 缓存，原日志保留；离线重试通过。首轮全量为 998 条、断言失败 0、错误 39、跳过 6，39 条均在 JUnit 关闭临时目录时遇到 AccessDeniedException。随后在允许测试临时文件访问的执行环境重跑，全量通过；未修改测试、跳过项或应用配置。

## 证据与边界

本轮日志、报告、截图、独立 JAR 与哈希快照使用已有 `target/ops/` 中 `replay-20261014-*` 前缀。当前仓库尚无首个提交，以输入与交付文件 SHA256 快照核验改动范围。

交付快照为 `replay-20261014-delivery.json`，独立复核为 `replay-20261014-verification.json`。与前一层 `ops-20261014-delivery.json` 比较，生产输入仅修改 RunController、RunQueryService、NodeRunRepository 和 console.html，新增 3 个 DTO 与 1 个测试；其余改动为规范/说明文档。配置、依赖、Compose 和全部迁移逐一保持基线哈希。快照不复制日志、请求正文、输入输出或环境属性。

不修改 schema/迁移、配置、凭证、CI/CD 或依赖，不改变调度、状态机、SSE 和审批执行语义。账号与数据归属隔离、真实模型质量和真实厂商兼容性仍按既有计划后置；本轮 mock 结果不构成这些能力的验收。
