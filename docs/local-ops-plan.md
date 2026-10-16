# 使用与运维收口计划

状态：2026-10-14 用户在第二层验收后授权“继续下一步”，现已完成，实际结果见 `local-ops-validation.md`。本切片只完善本地入口、运维说明和 mock 演示，不扩大生产功能或改变既有部署。

## 范围与目录

| 位置 | 约定 |
| --- | --- |
| `script/offline-engine.ps1` | 统一 `Check / Start / Status / Stop`，默认 Check；JDK 21、既有 JAR、PG/Redis 自检；启动等待就绪与实例身份保护 |
| `script/offline-engine-common.ps1` | 无启动副作用的共用函数、固定配置、诊断白名单、Windows 参数引用及受控子环境 |
| `script/offline-engine-worker.ps1` | 在隔离环境内启动 Java、重定向日志并写实例描述，只接受本地合法 launch spec |
| `script/verify-offline-launcher.ps1` | 不依赖真实厂商的启动配置/参数/环境隔离与失败门禁验证，不引入 Pester |
| `script/verify-offline-demo.ps1` | 只操作此实例的合成用户/会话/工作流/知识库，覆盖对话、幂等、SSE、记忆预览、RAG、音频和审批/取消 |
| `docs/local-ops-*.md` | 本计划、运行手册 `local-ops-runbook.md`、验收 `local-ops-validation.md` |
| `target/ops/` | marker 状态/诊断/演示报告、日志、合成附件与交付哈希快照；不删除旧文件/记录 |

所有新 ps1 使用 UTF-8 BOM，兼容 Windows PowerShell 5.1 和 PowerShell 7。无新依赖、全局安装或系统设置变更；不改 `.env`、真实密钥、CI/CD、数据库 schema/迁移、生产 Java/配置/API，不读取旧 target 启动器或真实凭证。运行仅使用已有本地 PostgreSQL V10 和 Redis；本机已安装 Docker Desktop 可启动，缺镜像/容器/schema 时报告失败，不拉取镜像、不初始化/迁移数据库。

## 启动契约

1. 默认监听 `127.0.0.1:8083`，端口可选择但不得抢占。使用绝对 JDK 路径并校验主版本 21，JAR 存在且记录 SHA256。marker 只允许短的英文/数字/连字符，输出限制在项目 `target/ops/`；已有 marker 的启动描述不覆盖；现存输出目录链不得为 junction/符号链接等重解析点。
2. Check 核对 JDK/JAR、目标端口、已有 Docker 容器及本机 PG/Redis、只读查询 Flyway 成功版本 V10 和必需表、Redis PING。Docker 命令显式使用本机 Docker Desktop named pipe，不继承远程 context/host。数据库查询限定 SELECT、设置只读及短超时；不回显 SQL 异常原文、容器环境或数据库内容。任何必需项失败返回非零和固定原因，绝不将未知状态算通过。
3. 子进程只接收最小 OS 环境白名单；不继承 `AIFLOW_*`、`SPRING_*`、`DB_*`、`REDIS_*` 或 JVM 注入变量。只加载 `classpath:/application.yml`，禁用额外 profile/config/import；固定本地开发数据库连接、Redis 和文件输出位置，不接受任意 JVM/Spring 参数。
4. 固定 mock chat/embedding/audio；关闭所有既有 HTTP 端点、探活、云知识库、OCR、对象存储、工具/MCP/skills、工具审计及落库清理、摘要/长期抽取、审批通知和后台超时扫描，webhook 的 URL/secret 显式留空，避免触及共享库旧审批/审计或解析外部地址。保留窗口记忆与人工审批供演示。音频显式启用 mock。Flyway=false、JPA DDL=none、SQL init=never；显式保留 DB/Redis 健康指示器。
5. 此入口是单实例本地演示：持久 dispatch/worker 关闭，避免认领既有调度任务；Redis 事件、会话锁和额度使用既有本地 Redis。多实例部署仍按既有 M4-3 设计单独操作，不能把本入口当生产配置。
6. Start 写固定配置 spec、在干净子环境启动、等待有界 health，并核对 capabilities/chat+embedding+ASR+TTS 均为 mock，以及事件/调度状态符合约定。核对端口确由该 Java PID 在 127.0.0.1 监听，拒绝借其他服务健康响应通过。就绪失败返回非零；仅在核实 PID、创建时间、可执行文件和 marker/命令一致后停止本次子进程，保留日志。
7. Status/Stop 依实例描述核对同一进程身份，PID 复用或描述异常时拒绝操作。Stop 只结束自己启动的本地 Java，保留状态及产物；不操作其他服务/容器，不删除任何记录。

## 演示与门禁

演示必须指定统一入口的 marker，先验证实例完整身份、URL/监听端口的 PID 归属与严格 mock 候选配置，再创建唯一前缀的合成数据；不接受普通服务临时降级到 mock 作为离线证明。JSON 请求显式 UTF-8；HTTP/SSE 设置超时、请求仅允许本机 origin且不跟随重定向。演示报告只保存检查名、PASS/FAIL、合成 ID、计数/时序、受控错误类名，不保存提示词/回复/原始 SSE/音频或错误正文。WAV 在内存中提交转写，不额外留存。

- 对话提交成功、运行收口、重复 clientMsgId 返回同一运行/消息；最终 error 为空。
- SSE 从零回放序号连续、DELTA 与最终回复一致、终态唯一；Last-Event-ID 续传核对完整后缀。
- 一轮后记忆预览有两条历史，本轮输入仅一次，角色/统计准确。
- 合成文本入库 INDEXED，本地 mock 检索有来源/编号映射；空库不伪造引用。独立 RAG 工作流验证节点链路，不评分 mock 语义质量。
- mock TTS WAV 头/PCM16k/单声道/16bit/响应头、同字节 ASR，以及 TTS→ASR 工作流；输出不携带临时句柄或二进制。
- 人工审批先确认 WAITING_APPROVAL，再分别批准恢复和取消，验证持久状态及不重放；不通过延时赌取消时序。

启动器门禁覆盖坏 marker/端口/JDK/JAR、环境隔离、固定配置与参数引用、缺前置条件非零和状态身份保护。实际验收需启动本机实例完成整条演示，再由自己停止并验证停止态。既有启动初始化器可能补建 JAR 内缺失的默认示例工作流版本，不覆盖已有定义；演示只新增独有前缀的合成业务记录，保留用于核验；不清理或逻辑重置已有数据。生产代码不变，复用第二层 975 条基线；按实际脚本改动运行双 PowerShell 语法/门禁和端到端验证，不为文档改动重复全量 Java 测试。

## 后续

交付后再决定真实厂商激活或其他功能扩展。厂商协议/账号/额度/网络需按提供的环境小样本联调；本层不接付费模型，不深化账号权限，不修改生产部署。
