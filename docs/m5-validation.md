# M5 第一批验证记录

> 本文记录 M5-A 与 M5-B 当前可在没有真实厂商额度、语音协议和声纹资料时验证的范围。真实模型、真实 ASR/TTS sidecar 和声纹能力不在本次验收结论内。

## 已交付

### M5-A：离线 profile 与出网门禁

- `offline` profile 将默认路由收敛到 `local -> mock`，关闭云端 chat、embedding 和云端知识库装配。
- `OfflineEgressGuard` 只允许 loopback 的 HTTP(S) endpoint；拒绝远端地址、非 HTTP(S)、URL 用户信息、query 和 fragment。
- 远端 chat provider 不注册、不探活；embedding 远端档位被跳过；本地模型未启动时能力报告为 `unavailable`。
- 离线 profile 的 loopback 隔离不能由配置开关关闭；大小写不同的 `localhost` 仍按 loopback 处理。
- `script/verify-m5-offline.ps1` 只使用本机 Maven 缓存和项目测试，不下载模型、不读取或输出云端凭证。

本轮 `OfflineEgressGuard` 只管理 chat、embedding 和云端知识库 provider。工具、MCP、skills、审批通知/webhook、OCR、对象存储等旁路仍由各自开关控制；因此 `offline` profile 不单独宣称全进程无外网。需要严格无外网运行时，使用既有 offline-engine 启动器，或逐项关闭旁路能力。

验证口径还包括：显式设置 `AIFLOW_OFFLINE_ENABLED=false` 是关闭 offline profile 的部署选择；在保持 `enabled=true` 时，`loopback-only=false` 和远端 `allowed-hosts` 仍不能绕过 loopback 门禁。

### M5-B：本地 ASR/TTS sidecar 抽象

- `LocalAsrProtocol` / `LocalTtsProtocol` 负责协议字段编码与解码，传输层固定为 loopback JDK HTTP。
- 只有显式选择 `aiflow.audio.mode=local`、开启 local sidecar、配置 loopback endpoint、通道 provider 和协议 bean 齐全时，才装配本地 provider。
- endpoint、协议或 sidecar 不可用时能力保持 `unavailable`，不会静默回退 mock；输入 WAV 校验、大小/时长限制、超时、重试、并发和错误边界复用 D1/D2 既有实现。
- 带版本路径的 base URL（例如 `/v1`）会保留前缀并拼接通道路径；不会因为 URI `resolve` 的文件路径语义丢失版本段。

## 自动化验证

以下定向测试已在本机 Maven 离线缓存中通过：

| 验证 | 结果 |
| --- | ---: |
| `LocalAudioSidecarConfigTest` | 5/5 |
| `LocalAudioHttpProtocolTest` | 2/2 |
| `AudioConfigTest` | 24/24 |
| `AudioApiIntegrationTest` | 22/22 |
| `AudioCapabilityApiIntegrationTest` | 1/1 |
| `AudioWorkflowIntegrationTest` | 2/2 |
| `OfflineProfileContractTest` | 3/3 |
| 受影响音频与 M5 组合 | 54/54 |

第一轮 M5 组合验证为 54/54；针对本轮门禁、兼容构造器和 local 协议包装器收口，复跑了 85 条受影响测试（offline profile、local sidecar、能力接口、EndpointHealth、HTTP 音频协议与传输）：**85/85 通过**。

另有以下静态检查通过：

- `git diff --check`
- `script/verify-m5-offline.ps1` PowerShell 语法解析
- `script/verify-m5-offline.ps1` UTF-8 BOM 检查
- `script/verify-m5-offline.ps1` 实际执行：`OfflineProfileContractTest` 3/3，脚本 PASS
- Maven 编译、目标测试与 `-DskipTests package` 在已有缓存中 `BUILD SUCCESS`；生成的 jar 含 offline profile、出网门禁和 local 音频 SPI 类。
- 离线 profile 下已配置但尚未完成首次探活的 loopback chat/embedding endpoint 初始报告为 `down/unavailable`；探活成功后才恢复 `up`。
- 离线门禁强制 loopback，不能通过 `loopback-only=false` 或远端 `allowed-hosts` 绕过；sidecar 的 `/v1` base path 会保留在 ASR/TTS endpoint 中。

本轮使用本机已有的 JDK 21 与 Maven 3.9.16（仅通过命令行临时设置 `JAVA_HOME`，没有安装或修改系统配置）。最新全量 `mvn test` 共运行 1144 条，业务断言失败 0 条，但有 39 条在 JUnit 临时目录清理阶段因 Windows `AccessDeniedException` 报错，Maven 以 1 退出；这些错误集中在测试扩展收尾，不是 M5 测试失败。第一轮 M5 与受影响音频组合为 54/54，收口后定向组合为 85/85。

## 尚未验收

- Ollama/vLLM 等真实本地 chat、embedding 服务的端到端 RAG → chat 验证。
- 真实本地 ASR/TTS sidecar 的请求字段、响应字段、超时、非法响应和音频 round-trip 联调；当前协议 SPI 不代表任何厂商协议已经接通。
- 声纹 1:1 provider、阈值、FAR/FRR、模板加密、删除与保留策略、访客权限矩阵和数据库 schema。资料齐全并单独确认 schema 变更后再进入 M5-C。

## 下一步

1. 提供本地模型和 ASR/TTS sidecar 的协议样例后，用 loopback 桩补齐真实字段契约测试，再做单样本联调。
2. 在具备 JUnit 临时目录清理权限的 Windows 环境重跑全量 `mvn test`，确认上述收尾错误消失；本轮已尝试将 `java.io.tmpdir` 指向项目 `target/ops/`，但 JUnit 仍使用系统临时目录，因此不把这次全量结果记为全绿；打包命令已通过。
3. 声纹资料和权限矩阵确认后，先更新设计文档，再单独评审 provider、存储和迁移方案。
