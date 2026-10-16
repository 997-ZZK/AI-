# 运行回放与失败定位：最小切片方案

状态：**2026-10-14 已实现并完成本机验收**。全量 998 条、0 失败/错误、6 跳过，真实回放 API 23/23、原演示 43/43、浏览器 22 项通过，证据与边界见 `run-replay-validation.md`。用户选择暂缓真实厂商联调，本切片不依赖模型账号或额度。

## 要解决的问题

`node_runs` 已保存每次节点尝试的状态、输入、输出、错误和起止时间，但 `RunController` 目前只提供整体运行查询、SSE、审批及取消；`RunQueryService` 没有节点快照查询。控制台的对话区将 SSE 写进文本日志，没有按历史 runId 查看节点尝试的面板。

新增一个事后排查入口：输入 runId，即可查看运行状态、每次节点尝试及失败位置。Redis 事件有最多 2000 条/24 小时的保留边界，历史运行展示应以已有 PostgreSQL 快照为依据，不依赖事件仍然存在。

这里的“回放”指查看已有执行记录，不重新执行工作流，也不把快照模拟成完整实时事件流。

## 实施范围

### R1：只读查询

- 保留现有 `GET /api/v1/runs/{runId}` 契约。
- 新增 `GET /api/v1/runs/{runId}/nodes?page=0&size=50`：返回节点尝试摘要，默认每页 50 条、最多 200 条，含分页信息；非法分页参数 400、不存在运行 404、合法空页返回空列表。
- 摘要展示 nodeRunId、nodeId、nodeType、attempt、status、startedAt、endedAt 和可计算的耗时，不批量返回输入/输出正文。数据库完成分页，按起始时间与唯一 ID 稳定排序；空时间的顺序显式规定。
- 新增 `GET /api/v1/runs/{runId}/nodes/{nodeRunId}`：按需读取单条输入、输出及已记录错误；必须同时核对所属 runId，跨运行查询返回 404。
- Controller/DTO/服务沿用 `api` / `api/dto` / `api/service`，查询扩展沿用 `persistence/repo/NodeRunRepository`；使用只读事务，不向引擎核心引入 Web/JPA。

接口约定：响应继续使用 `ApiResponse`；列表 data 为 `{items, page, size, hasNext}`，items 每条含 `nodeRunId / nodeId / nodeType / attempt / status / startedAt / endedAt / elapsedMs`。列表使用数据库字段投影，不读取 input/output/error；详情 data 为同样的扁平字段并增加 `input / output / error`。分页 `page>=0`、`1<=size<=200`，超过 JPA 整数 offset 的组合也返回 400。排序为 startedAt 非空在前、startedAt 升序、ID 升序；未结束、缺开始时间或负时间跨度的 elapsedMs 为 null，正常结束才返回毫秒总历时。

### R2：控制台面板

- 在现有静态控制台增加“运行回放”区域，支持手输 runId；发送消息成功后自动回填最近 runId。
- 展示整体运行状态、起止时间、错误，以及节点尝试表格；支持翻页、手动刷新和展开单条快照。
- 区分同节点的多次 attempt，标明等待审批、降级、失败和取消。进行中的运行可刷新，不采用无界轮询。
- 切换 runId 时清空旧详情并防止较慢的旧响应覆盖新结果；使用 `textContent` 渲染业务文本，避免将节点内容作为 HTML。
- 输入/输出只在用户展开后展示于当前页面，不新增浏览器持久化、下载导出或正文日志副本。

## 展示口径

- 只展示已留存的节点尝试；条件分支未执行且没有快照的节点不伪造记录。
- 相同时间的稳定排序不等于严格因果顺序；不从排序推导并行关系。
- 未结束记录的最终耗时为空；审批节点的时间跨度可能包含人工等待，不冒充纯模型延迟或 CPU 耗时。
- 运行与节点记录可能在执行过程中先后提交，刷新展示当前读到的状态，不宣称整段执行原子快照。
- 保持当前本地开发 API 的访问边界；完整账号权限与数据归属隔离仍按用户偏好后置。

## 验证与交付

1. 新增查询集成测试，覆盖成功与不存在、分页与同时间稳定排序、空记录/空时间、重试尝试、跨运行详情拒绝、非法参数 4xx，以及 GET 不修改记录。
2. 运行相关定向测试与 `ErrorResponseIntegrationTest`，再运行离线全量测试和打包；新 JAR 独立保存，保留此前交付包。
3. 使用现有本机 PostgreSQL/Redis 和强制 mock 入口，通过正常 API 新增独有前缀的合成工作流和运行。覆盖成功、可控失败/重试、审批挂起/恢复和取消；不靠真实模型制造故障。
4. 真实浏览器验收面板、分页/详情/刷新、错误 runId、正文安全渲染和快速切换结果隔离，并检查无 JavaScript 错误；回归对话 SSE、审批待办/批准/拒绝/事件。
5. 同步 `AGENTS.md`、`architecture.md`、`roadmap.md` 和 README 的实施/验收状态；新增 `docs/run-replay-validation.md`。合成报告、日志、截图、独立包及路径/哈希快照放已有 `target/ops/`，使用 `replay-20261014-*` 前缀，不清理旧产物。

## 范围边界

本轮只增加只读接口和现有控制台面板。不改数据库 schema/迁移，不修改 `.env`、真实密钥或 CI/CD，不添加依赖/前端框架，不改运行状态机、调度、SSE 或审批执行语义，不引入 OpenTelemetry 服务和外部模型调用。开始实施前先将上述目录及验证约定写入项目 `AGENTS.md`。
