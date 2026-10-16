# M5 设计契约：离线全链路、本地语音与声纹边界

> 状态：设计稿，未宣称 M5 功能已交付。本文先冻结接口、降级和验收边界，再进入实现。
>
> 目标：在没有云端凭证、真实模型额度和厂商语音协议的情况下，完成可验证的本地链路；真实厂商接入和声纹存储在资料齐全后单独推进。

## 1. M5 拆分

M5 拆成三个互相解耦的切片：

| 切片 | 内容 | 当前可做性 | 交付判定 |
| --- | --- | --- | --- |
| M5-A | 本地 chat + embedding + RAG 的离线 profile、出网门禁和一键验收 | 立即可做 | 无云端配置和凭证时，离线 profile 能完成检索到回答，并报告所有 provider 状态 |
| M5-B | 本地 ASR/TTS sidecar 的 provider SPI、装配和 loopback 契约桩 | 可做协议与装配 | `local` 模式复用现有音频校验、超时、错误和句柄门禁；没有 sidecar 时明确 `unavailable`，不自动假装可用 |
| M5-C | 声纹 1:1 provider、访客权限矩阵和生命周期接口 | 先做接口设计 | 未配置自动禁用；验证失败降级访客；真实算法、阈值、存储和迁移等资料齐全后再实现 |

本轮默认先做 M5-A，再做 M5-B。M5-C 只冻结契约和风险清单，暂不创建数据库表、不保存声纹向量、不接入实时音频或 WebSocket。

## 2. M5-A 本地离线 profile

### 2.1 配置语义

增加独立的 `offline` 配置入口（profile 或等价的显式环境变量集合），其约束为：

- provider 路由只允许 `local` 和 `mock`，云端端点关闭，不读取云端密钥；
- chat 与 embedding 均使用 OpenAI 兼容本地端点，端点地址和模型名来自环境变量；
- RAG 使用已有本地向量存储和检索后处理，阈值沿用当前 embedding 模型的校准值；
- 能力报告必须区分 `local`、`mock`、`disabled`，不能把本地端点未响应当成已可用；
- 启动与验收脚本不得访问外网，失败时报告固定分类和端点 id，不打印 URL 中的凭证或响应正文。

`offline` profile 的出网保证范围是 chat、embedding 和云端知识库 provider。工具、MCP、skills、审批通知/webhook、OCR、对象存储等旁路由各自的开关控制，profile 本身不构成全进程无外网证明；需要严格无外网运行时，使用既有 offline-engine 启动器，或逐项关闭这些旁路能力。

显式设置 `aiflow.offline.enabled=false`（例如环境变量 `AIFLOW_OFFLINE_ENABLED=false`）表示关闭离线 profile，属于部署方切换运行模式；在 `enabled=true` 时，`loopback-only=false` 或增加远端 `allowed-hosts` 不能放开远端端点。

不新增模型依赖，不在仓库中下载或提交 Ollama/vLLM、ASR/TTS 模型文件。模型由使用者在本机另行安装。

### 2.2 验收

离线验收至少覆盖：

1. 无外部网络和云端凭证时，配置加载成功，云端档位为 disabled；
2. 本地 embedding 入库并完成 RAG 检索，命中结果带可追溯 citation；
3. 本地 chat 完成一次工作流运行；本地端点不可用时按既有路由降级到 mock 或固定失败语义；
4. 由 M5-A 管理的 chat/embedding HTTP 请求只命中 loopback/明确允许的本地地址；
5. `/api/v1/capabilities` 与实际 provider 装配一致。

## 3. M5-B 本地 ASR/TTS

### 3.1 边界

本地语音 provider 只定义厂商无关的 sidecar 边界，继续复用 `AsrProvider`、`TtsProvider`、`AudioOperations`、`WavPcmValidator` 和现有错误分类。sidecar 协议必须由配置提供：

- loopback base URL、路径、请求/响应 JSON 或二进制格式；
- 超时、最大请求体、音频格式和模型名；
- 若 sidecar 需要鉴权，只保存环境变量名，不读取或打印密钥值。

当前不猜 whisper.cpp、faster-whisper、Piper、CosyVoice 或任何云厂商的字段。没有协议配置时 provider 不注册或报告 `unavailable`。

### 3.2 失败和降级

- 输入校验、WAV 复验和输出媒体类型沿用现有 D1/D2 契约；
- sidecar 连接失败、超时、非法响应统一映射为固定 `provider unavailable`/`invalid response` 分类；
- 不把 sidecar 原始错误正文、请求 URL、音频正文或凭证写入日志、运行快照或 API 错误；
- mock 只有在显式配置 `mock` 或离线测试 profile 中启用，不能因为 local sidecar 不可用而静默伪造生产成功。

### 3.3 验收

使用本地 JDK loopback 桩覆盖：成功转写/合成、超时、非 2xx、非法 JSON/音频、超过限额、sidecar 未启动；再验证能力接口、API 和 `asr.transcribe`/`tts.speak` 节点使用同一门禁。

收口验证继续使用既有 `config` / `integration/audio` 测试目录：按配置契约覆盖 `application-offline.yml`，并将 local 协议包装器、HTTP provider 与 JDK loopback 桩串联。缺少或重复协议 bean 时仅通道不可用；测试使用合成 WAV 和合成文本，不建立任何真实厂商兼容承诺。全量测试优先把临时目录放项目已有 `target/ops/` 下，保留正常 JUnit 清理，不删除旧产物。

## 4. M5-C 声纹与访客权限

### 4.1 Provider SPI（设计）

声纹只做 1:1 确认，不做开放集识别。抽象需要表达：

- `enroll(subjectId, sample)`：录入或更新一个主体的模板引用；
- `verify(subjectId, sample)`：返回 `VERIFIED`、`REJECTED`、`UNAVAILABLE` 或 `INVALID_SAMPLE`，附固定原因和置信度区间；
- `delete(subjectId)`：可验证删除并使后续确认失效；
- provider 能力和模型版本只出现在诊断元数据中，声纹向量绝不出现在日志、SSE、运行快照或普通 API 响应。

### 4.2 访客权限矩阵（初稿）

| 身份状态 | 允许 | 拒绝或降级 |
| --- | --- | --- |
| `guest`（未确认/确认失败） | 公共对话、无敏感工具的基础工作流 | 私有记忆、账户级资料、敏感工具、声纹管理 |
| `authenticated`（已有账号身份） | 账号允许的普通能力 | 需要声纹确认的高风险操作 |
| `voice_verified` | 账号权限范围内的声纹门禁能力 | 超出账号授权范围的操作 |
| `admin` | 管理和删除操作 | 仍需遵守审计与数据生命周期 |

声纹失败只降级到 `guest`，不得自动把失败当成另一个用户。声纹确认不能替代现有 token 身份认证；最终策略由身份主体、资源归属和操作风险共同决定。

### 4.3 未决资料和红线

以下资料缺失前不实现真实 provider 或 schema：算法/sidecar 形态（CAM++、ECAPA-TDNN 或云 API）、采样率和质量门槛、阈值与 FAR/FRR 目标、模板加密和密钥托管、保留/删除策略、账号与访客的最终权限矩阵。创建声纹表、迁移或保存生物特征属于数据库与敏感数据变更，必须另行确认。

## 5. 实施顺序与验证

1. 先实现 M5-A 文档对应的 offline profile、配置门禁和离线验收脚本；
2. 再实现 M5-B 的 local 音频 provider 装配和 loopback 契约测试；
3. 收集真实语音协议、模型和声纹资料后，补齐 M5-C 的实现方案，再单独确认 schema 与权限变更；
4. 每个切片完成后运行受影响的定向测试、`mvn test` 和 `mvn -q -DskipTests package`，并更新 `docs/roadmap.md`、`docs/architecture.md` 与对应 validation 文档。

本设计不代表真实模型、真实厂商端点、声纹算法或生产权限已经验收。
