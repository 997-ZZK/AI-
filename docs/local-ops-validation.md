# 使用与运维收口验收

状态：**2026-10-14 已完成本机验收**。Windows PowerShell 5.1 / PowerShell 7 启动器门禁各 47/47、真实 PostgreSQL / Redis 演示各 43/43、失败路径 11/11；两种 shell 的启动、交叉 Status/Stop 和停止后复查均通过。范围与配置契约见 `local-ops-plan.md`，使用说明见 `local-ops-runbook.md`。

## 交付范围

- `script/offline-engine.ps1`：默认 Check，以及 Start / Status / Stop。
- `script/offline-engine-common.ps1`、`offline-engine-worker.ps1`：固定 mock 配置、子环境白名单、参数引用、进程身份核验和隔离启动。
- `script/verify-offline-launcher.ps1`：启动器失败门禁、参数与环境隔离、独立就绪响应夹具。
- `script/verify-offline-demo.ps1`：通过真实本机 API 验证对话、幂等、SSE、记忆、RAG、音频、审批与取消。

使用既有 JDK 21.0.12.1、已有 PostgreSQL V10 / Redis 和第二层已打包 JAR；不改生产 Java、应用配置、依赖、schema/迁移或 CI/CD，不读取真实凭证，不调用真实模型。脚本只新增合成业务记录，保留旧产物和失败证据。

## 验证记录

| 验证 | 结果 | 证据（`target/ops/`） |
| --- | --- | --- |
| 真实只读 Check | 9/9：JDK/JAR、空闲端口、本机 PG/Redis、项目容器、V10/必需表/vector、Redis PING | `ops-20261014-preflight.log` |
| 启动器门禁 | PS 5.1 与 7 各 47/47，FAIL=0 | `ops-20261014-final-gate-{ps51,ps7}-report.json` |
| 真实合成演示 | PS 5.1 与 7 各 43/43，FAIL/NOT_RUN=0 | `ops-20261014-demo-{ps51,ps7}-report.json` |
| CLI 失败路径 | 11/11 | `ops-20261014-negative-final-report.json` |
| 最终生命周期 | PS5 启动 8083、PS7 启动 8084；互相查询并停止，最终均 STOPPED；命令均返回 0，启动不再等待 Java 退出 | `ops-20261014-detached-{start,status,stop}-{ps51,ps7}.log`、`ops-20261014-stopped-status-{ps51,ps7}.log` |
| 最终脚本语法/BOM | 5 个脚本 × 2 个 shell，共 10 项，0 语法错误、全部 UTF-8 BOM | `ops-20261014-syntax-report.json` |
| 交付基线与文件哈希 | 生产输入与第二层一致；复用原 JAR，不重新打包 | `ops-20261014-delivery.json` |

实测环境：Windows PowerShell `5.1.22621.3672`、PowerShell `7.6.5`、JDK `21.0.12.1`，既有 `aiflow-postgres` / `aiflow-redis` 容器。本轮启动了已安装 Docker Desktop 和这两个已有容器，没有拉镜像、重建容器、修改 schema 或执行迁移。测试引擎均已停止，基础设施和合成业务记录保留。

演示先核对 marker、完整进程身份、127.0.0.1 监听 PID 及严格 mock 候选，再注册四个独有前缀的工作流。43 项覆盖 UTF-8 对话/幂等、SSE 连续序号/唯一终态/全文/续传后缀/坏游标、窗口记忆预览、文档 INDEXED/本地引用/空库/RAG 工作流、内存 WAV TTS→ASR/API 头及音频工作流、同会话双审批挂起/释放锁/批准恢复不重放/取消收口/再次决策 409。报告不保存正文、原始 SSE、音频或异常原文。

11 项失败路径覆盖非法 marker/端口、缺失 JDK/JAR、端口占用下 Check/Start 返回非零、未知实例、拒绝停止其他进程且该进程仍存活、超长演示前缀，以及错误实例端口。最后一项报告为预期 FAIL（首项失败、42 项 NOT_RUN、未生成业务 ID）；不能将这份拒绝证据当作成功演示。

## 实际发现与修复

首次 Start 的服务 health 已 UP，但身份校验未通过：PowerShell 7 将 JSON 时间转换为 DateTime，旧比较误判；Windows Start-Process 又在固定命令末尾追加空格。现在精确比较 UTC ticks，只允许命令末尾空格/Tab，其他参数逐字核对；新增门禁拒绝 ±1 tick、非 UTC 值、参数或可执行文件变化。受工具沙箱限制的 CIM 查询仍失败关闭，真实进程验收在允许本机查询的上下文执行。

第二次 Start 因 Java 继承 worker 输出管道而返回固定失败码，已核实自有 Java 停止。随后发现额外宿主句柄也会被 WinPS5/.NET Framework 继承，使工具仍等待 EOF。最终 worker 在清洁环境中通过隐藏 ShellExecute 启动最终阶段，两阶段都校验 spec 并清理环境；Java 继续使用固定参数与独立文件日志。双 shell 最终启动均正常退出，Java 独立保持运行至统一 Stop。

独立审查还补齐了：拒绝现存目录重解析点；核对监听端口所属 PID；停止后确认退出才报告成功；worker 写描述失败回收自有进程；演示必须使用专用 marker 和完整 mock 候选；Prefix 限 1..15，派生 ID 请求前校验不超过数据库既有 64 字符限制。未通过放宽业务断言解决问题。

首轮失败描述/日志、第二轮失败材料和后续成功记录均保留。负路径汇总的首次手工装配漏计了错误端口项，后已修正汇总程序并重新验证该项，最终以 `negative-final-report.json` 的 11 项为准，旧 10 项报告保留。

## 复现与交付基线

先按运行手册 Check/Start，再在项目根目录运行；示例 marker 使用本次 Start 返回值，每份报告使用新文件名：

```powershell
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\verify-offline-launcher.ps1 -Prefix gate-new-ps51
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\verify-offline-launcher.ps1 -Prefix gate-new-ps7
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\verify-offline-demo.ps1 -Marker $demoMarker -Prefix demo-ps51
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\verify-offline-demo.ps1 -Marker $demoMarker -Prefix demo-ps7
```

复用第二层 JAR：`target/m4-3d/layer2-20261014-engine.jar`，SHA256 `a35bbd2f2751a3da304c40dc152487f7ace9eab3a332281d50460593949809a4`；默认 `target/aiflow-engine-0.1.0-SNAPSHOT.jar` 哈希相同。第二层 975 条（0 失败/错误、6 跳过）是既有回归基线，本轮仅改脚本/文档，没有重跑全量 Java 测试。交付快照记录相对路径、字节数、SHA256 和白名单统计；仓库尚无首个提交，Git diff 不能充当完整交付证据。

## 边界

此入口仅绑定本机回环地址、关闭鉴权和持久 dispatch/worker，依赖本机已有数据库与 Redis，不用于生产或多实例容量验证。启动初始化器可能补建缺失的默认示例工作流，不覆盖已有定义。Check 仅做自检；运行演示会通过正常 API 新增合成记录。

mock 结果只证明应用链路和协议契约，不证明真实模型回答、检索语义、转写或发音质量。真实厂商激活需后续提供端点/协议、模型名、凭证环境变量名、代理/VPN及额度约束后，按小样本单独验收。账号与数据归属隔离继续后置。
