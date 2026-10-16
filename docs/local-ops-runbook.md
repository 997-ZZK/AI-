# 本地离线运行手册

本入口使用 mock 对话、向量和音频，适合个人开发、功能演示和故障定位。它需要已有 PostgreSQL V10 和 Redis；“离线”指无需模型服务、厂商账号或互联网，不代表无需本机基础设施。生产部署、多实例调度与真实模型激活沿用各自设计，不能直接复制这里的演示配置。

## 前置条件

| 项目 | 当前本机约定 | 检查意义 |
| --- | --- | --- |
| PowerShell | Windows PowerShell 5.1 或 PowerShell 7 | 脚本 UTF-8 BOM，无额外模块 |
| Java | `D:\tools\jdk21\jdk-21.0.12.1+1`，必须 JDK 21 | 显式路径，避免 PATH 中其他 Java 版本 |
| 可执行包 | `target/aiflow-engine-0.1.0-SNAPSHOT.jar` | 使用已构建包，运行不依赖 Maven/下载 |
| PostgreSQL | 既有 `aiflow-postgres`，本机 15432，成功迁移至 V10 | 只读核对版本和必需表，不自动建库或迁移 |
| Redis | 既有 `aiflow-redis`，本机 16379 | 提供事件回放、会话锁与额度计数 |
| 本地服务端口 | 默认 `127.0.0.1:8083` | 不抢占 8080/其他已有服务 |

需要运行容器时，先打开已安装的 Docker Desktop，再启动已有容器：

```powershell
docker start aiflow-postgres aiflow-redis
```

此命令只适用于已经存在的本项目容器。缺镜像、容器或 schema 时先处理对应前置条件；统一入口不会自动拉镜像、执行 Flyway/DDL、删除数据或改系统设置。本轮不使用 MinIO、本地大模型、真实 embedding 或真实语音服务。

## 自检、启动与停止

在 `D:\work1\AIFlowEngine` 执行；可以将 `powershell` 换成 `pwsh`。不需要 dot-source `dev-env.ps1`，不需要 `.env` 或 API key。示例的 `-ExecutionPolicy Bypass` 只作用于本次 PowerShell 进程，不改系统执行策略。

```powershell
# 默认动作也是 Check；检查失败时返回非零，先看固定检查项
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\offline-engine.ps1 -Action Check

# marker 每次启动使用新值，历史实例和日志保留
$demoMarker = 'offline-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\offline-engine.ps1 -Action Start -Marker $demoMarker

powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\offline-engine.ps1 -Action Status -Marker $demoMarker

# 仅结束此 marker 对应且身份核对通过的 Java，不删除产物或业务记录
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\offline-engine.ps1 -Action Stop -Marker $demoMarker
```

可通过 `-JavaHome`、`-JarPath`、`-Port` 选择已有 JDK/JAR 和空闲本机端口。运行期间不要替换所选 JAR 或 JDK；Status/Stop 会重核路径和 JAR 哈希，包变化时拒绝操作。需要并行构建时，用 `-JarPath target/m4-3d/layer2-20261014-engine.jar` 选择已有独立交付包。没有任意 JVM/Spring 参数透传，也不接收厂商凭证。Status/Stop 必须提供启动时的 marker；PID 已退出显示停止，PID 复用或描述不一致时拒绝停止。

Start 只有在 health、四种 mock 能力和事件/调度状态核对通过后才报告成功。实例创建后若未就绪，会保留诊断和日志；脚本只在能够核实进程身份时停止该失败实例，身份不明不会终止进程。

## 固定运行行为

- 只监听 IPv4 回环地址，鉴权关闭，仅供本机演示。对话/向量/ASR/TTS 都明确为 mock，云端点和探活关闭。
- 子进程环境使用最小白名单，只加载 JAR 内 `application.yml`；本机 Spring/AIFLOW/数据库/JVM 注入变量不会传入。不会读取真实 API key、旧 target 启动器或外部配置文件。
- 工具/MCP/skills及其审计清理、云知识库、OCR、对象存储、摘要/长期抽取、审批通知和后台超时扫描关闭，避免触及旧审批/审计。窗口记忆、人工审批和 mock 音频可用。
- 持久 dispatch 和后台 worker 关闭，避免认领旧调度任务；本地会话队列执行新消息，事件/会话锁/额度由 Redis 提供。这里不验证跨实例调度或生产容量。
- Flyway 关闭、JPA DDL=none、SQL init=never，不进行 schema 修改。既有启动初始化器会补建缺失的默认示例工作流（不覆盖已有定义）；端到端演示通过正常 API 新增合成业务记录。音频工作流仍使用既有运行内临时句柄。

浏览器可打开 `http://127.0.0.1:8083/console.html`，健康检查为 `/actuator/health`，能力检查为 `/api/v1/capabilities`。端口修改后同步替换 URL。mock 只能证明流程接通，不用于评估真实回答、转写或发音质量。

## 端到端演示

保持实例运行后执行：

```powershell
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .\script\verify-offline-demo.ps1 -Marker $demoMarker -BaseUrl http://127.0.0.1:8083
```

脚本必须指定统一入口启动时的 `-Marker`，先核对进程完整身份、监听 PID、URL 端口和只含 mock 的候选配置，再创建唯一前缀的合成用户、会话、工作流和知识库，依次检查：对话与幂等、SSE 回放/续传、记忆预览、文本入库/本地检索/RAG 工作流、内存 WAV 合成/转写/音频工作流、审批批准恢复及挂起后取消。每步有超时和固定检查名；任一失败整次返回非零。

报告只存检查状态、合成 ID、计数/时序和异常类名，不存提示词/回复、原始 SSE、音频或服务端错误正文。业务库中的合成记录保留，脚本不清理或重置数据。重新执行会使用新前缀，可用 `-Prefix`（1..15 位英数/连字符，首位英数）和 `-OutFile` 指定本次名称；输出必须位于项目 `target/ops/`。

## 定位故障

| 现象 | 下一步 |
| --- | --- |
| Java/JAR 检查失败 | 核对 `-JavaHome` 下的 JDK 21 和 `-JarPath`；包不存在时按项目构建说明准备包 |
| Docker/PG/Redis 未就绪 | 确认 Docker Desktop 已运行、上述两个已有容器已启动、15432/16379 未被其他服务占用；不要自动重建或删卷 |
| schema 检查失败 | 核对既有 V10 迁移记录与必需表；本入口不执行迁移，需另行处理数据库准备 |
| 8083 已占用 | 查看已有实例状态，或换 `-Port`；不要按端口盲目终止其他进程 |
| marker 已存在 | 选择新 marker，保留旧状态与日志；不覆盖或删除历史产物 |
| 健康/能力/事件状态失败 | 查看该 marker 的启动诊断、stdout/stderr 日志；检查失败项，未通过时不要把实例当作就绪 |
| Status/Stop 身份不匹配 | 保留实例描述供核查；不要仅凭 PID 停止进程 |
| 某个演示检查失败 | 通过报告的检查名、合成 run/session ID 定位 API/运行快照；先确认运行实例和报告属于同次演示 |

`target/ops/` 保存本切片状态、诊断、日志和演示结果，不进 Git。日志仅由固定 mock 配置产生，不代表全项目日志都具备统一脱敏。分享材料优先选择只含白名单字段的报告和交付快照。

真实厂商联调时再提供：API 协议/端点、模型名与能力、凭证环境变量名、网络是否需代理/VPN及超时/限流约束。无需把 key 写进脚本或文档；真实启用不通过这个强制 mock 入口。
