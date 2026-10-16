# M4-3D：ASR/TTS 最小链路实施方案

状态：**D0、D1 已完成并验收（2026-10-13）**。D1 实施下述 mock 同步 API 契约；验证记录见 [`m4-3d-d1-validation.md`](m4-3d-d1-validation.md)，D0 证据见 [`m4-3d-validation.md`](m4-3d-validation.md)。真实厂商协议仍未确认；对应 HTTP 适配与协议桩后置，不填写 endpoint、不读取或修改任何密钥。

## 1. 目标与范围

M4-3D 先交付短音频同步转写和短文本同步合成两条独立能力，随后才接工作流节点和控制台。第一阶段不把音频并入会话消息、短期/长期记忆或图片附件存储，不新增数据库表，不做双向实时音频、本地 ASR/TTS、声纹和 Gatling 真实语音压测。

音频直接作为一次请求的字节流校验和传输；TTS 结果直接返回音频字节及媒体类型。音频正文、base64、厂商响应原文和凭证值不得进入日志、运行快照、事件或验证报告。

## 2. 真实适配的先决信息

在真实厂商适配及其 HTTP 协议桩实现前，必须确认并记录以下非敏感信息；D0 的纯 Java / mock 不依赖这些信息：

- 厂商产品和地域；ASR、TTS 的 base URL、路径与鉴权头形态。
- ASR 模型、同步或异步协议、请求字段、响应字段、错误码和超时限制。
- 音频容器/编码、声道、采样率、最大字节数、最大时长及是否支持 WAV PCM。
- TTS 模型、voice、输出格式、采样率、文本字符上限和计费口径。
- 凭证只记录环境变量名，例如 `AIFLOW_AUDIO_API_KEY`，不记录变量值。

不能从现有 qwen 对话网关、`/chat/completions` 或 OpenAI 兼容配置推断语音协议。百炼具体产品和官方协议确认前，不猜路径或字段。

## 3. 分阶段交付

### D0：核心端口与 mock 契约（已完成）

- 新建 `engine/audio`：`AsrProvider`、`TtsProvider`、音频输入/输出 DTO、格式与大小/时长校验、脱敏异常和音频限制配置。
- 新建 `engine/integration/audio`：mock ASR 只给固定模拟结果，mock TTS 返回确定性静音 WAV；两者只证明接口与格式链路，不代表识别或合成质量。厂商 HTTP contract stub 待协议确认后实施，不自造协议。
- 新增 `aiflow.audio.*` 独立配置，默认 `enabled=false`；不复用 `ProviderProperties`、`ProviderRouter` 或 `AttachmentStore`。
- 能力报告新增 `asr` / `tts` 通道：未启用或未配置时为 `unavailable` 并给出原因；mock 只能标明 mock 能力，不伪装真实厂商能力。
- D0 默认 WAV、未压缩 PCM、单声道、16kHz、16bit、最多 30 秒、文件最多 5MiB；TTS 文本最多 2000 个 Unicode 码点。WAV 校验包括 RIFF 总长、块边界与 padding、唯一 fmt/data、byteRate/blockAlign、非空且完整的采样帧；时长向上取整到毫秒，超过上限直接拒绝。未来真实生效值取厂商限制和本地限制的较严者。
- 本地 PCM 限制允许配置采样率 1..192000Hz、1/2 声道、8/16/24/32bit；输入必须与所配参数完全一致。时长限制至少 1ms，且转换毫秒不得溢出。mock TTS 固定 16kHz/单声道/16bit，输出静音 100..2000ms，`voice` 在请求中保留但不影响输出。
- `available()` 只说明限制允许至少一个最小样本：mock ASR 要求一个完整 PCM 帧；mock TTS 要求固定格式、至少 100ms 和 3244bytes。限制不足时能力报告为 `unavailable`；可用状态不保证任意请求都通过限制校验。

### D1：同步 API（已完成并验收）

本切片仅把已验收的 mock 端口接到两个同步 HTTP API；真实厂商协议与凭证、工作流节点、控制台、数据库和音频留存均不属于 D1。Controller 延续现有落位 `api`，DTO 放 `api/dto`，应用服务放 `api/service`，校验原因仍在纯 Java `audio` 模块。

| 接口 | 请求 | 成功响应 |
| --- | --- | --- |
| `POST /api/v1/audio/transcriptions` | `multipart/form-data`，必填 `file`；文件 part 媒体类型支持 `audio/wav`、`audio/x-wav`、`audio/wave`；model 使用通道配置 | HTTP 200，`ApiResponse` JSON：`text`、`provider`、`model`、`durationMs`，以及大小/时长/PCM 参数等白名单限制元数据 |
| `POST /api/v1/audio/speech` | JSON：必填 `text`，可选 `voice`、`format`；首版只接受 `wav`/`audio/wav`，model 使用通道配置 | HTTP 200，直接返回 WAV 字节；`Content-Type: audio/wav`、准确的 `Content-Length`、`Cache-Control: no-store`、固定文件名 `speech.wav`；`X-Audio-Provider`、`X-Audio-Model`、`X-Audio-Duration-Ms` 使用受控元数据 |

请求中不开放任意 model 到响应头的透传；响应头中的配置值必须通过格式/长度校验。ASR 响应与 TTS 请求 DTO 的 `toString` 隐藏正文；两个成功响应均设置 `Cache-Control: no-store`。TTS 错误仍返回 JSON `ApiResponse`，包括调用方使用 `Accept: audio/wav` 时，不能因错误协商退化成 406。

`text` / `voice` / `format` 只接受 JSON 字符串（可选字段允许 null），数字、布尔、对象与数组返回 400；仅在音频 DTO 上关闭隐式标量转字符串，不改变其他接口的 Jackson 行为。服务复验 provider 输出的媒体类型、WAV、大小、时长及元数据；上游无效输出归 502，不能误报为调用方的 400/413/415。

**错误映射**：先给 `AudioValidationException` 增加稳定 `Reason`，不匹配中文异常消息。缺参数、空文件/文本、结构损坏返回 400；媒体类型/非 WAV 文件头、PCM 格式或参数不支持返回 415；字节、时长、Unicode 码点超限返回 413；通道关闭、未装配或不可用返回 503。`AudioProviderException` 的超时映射 504，其他上游失败映射 502；D1 用失败桩验证，无真实网络调用。业务码补充 413/502/504，错误正文与日志仅输出固定脱敏信息，不保留外部异常 cause 或厂商原文。

稳定校验原因为 `INVALID_REQUEST` / `MALFORMED_WAV`（400）、`UNSUPPORTED_MEDIA` / `UNSUPPORTED_PCM_PARAMS`（415）、`SIZE_LIMIT` / `DURATION_LIMIT` / `TEXT_LIMIT`（413）。Web 层只按原因分类；调用方无法把正文经校验异常消息带入响应。响应体业务码与 HTTP 状态保持一一对应。

调用与能力报告采用同一门禁：根开关/通道开关打开、mode 为 mock、配置 provider 为 mock、实际端口 id 为 mock 且 available 才可用。获取 bean 或探测 id/available 抛异常时，音频 API 返回固定 503，能力报告仅将该通道标 unavailable；不探测关闭态通道，不把探测异常原文带入日志。

**上传限制**：保留图片/文档现有全局 multipart 10MB/12MB 限额。音频先检查 `MultipartFile.getSize()`，再按有效上限限长读取（最多上限加 1byte）并校验 WAV；默认有效上限 5MiB。音频路径的 Servlet 上传超限返回 413，既有图片/文档错误状态保持其已测试契约。Servlet 解析可能使用请求期临时文件，由容器清理；应用不写音频原件、附件、会话/记忆或数据库。此临时解析行为必须在验证记录中注明，不能宣称整个 HTTP 上传过程绝无磁盘访问。

**验收**：新增 mock API 集成测试覆盖正常转写/合成、缺 part/空值、MIME/文件头/结构/PCM 参数、5MiB/30s/2000 码点边界、默认关闭/单通道关闭/不可用、固定 provider 失败原因、二进制长度与响应头、错误 JSON 与日志脱敏。Servlet 解析限额另用真实嵌入服务器和合成上传验证，不能仅凭 MockMvc 判通过。必跑 `ErrorResponseIntegrationTest`、`GlobalExceptionHandlerSseTest`、现有附件/文档接口回归，再串行跑全量（启用本地 Redis 集成）与离线打包；不运行真实模型压测。

### D2：工作流和控制台（已批准实施，2026-10-13）

- 新增 `asr.transcribe`、`tts.speak` 节点，分别声明 `asr` / `tts` 能力；节点变量只传文本或受控音频句柄，不把 byte[]/base64 放进 `NodeContext`、`node_runs` 或事件。
- 控制台只提供一次性短音频上传、文本合成和能力状态；不把音频伪装成会话历史消息。
- 真实厂商联调另行批准，只做一条合成样本和一条转写样本；不进入 Gatling，不承诺生产容量。

**节点契约**：`tts.speak` 的 `text` 为字符串模板（默认 `${input}`），可选字符串 `voice`、`format`（默认 `wav`）；model 取配置。输出固定 `audioHandle` 和 provider/model/durationMs/byteLength 等元数据。`asr.transcribe` 的 `audioHandle` 为字符串模板（默认 `${audioHandle}`），输出固定 `transcript` 和 provider/model/durationMs；不自动改写 input/reply，不发音频 DELTA。定义中的非字符串参数、未知参数、非 WAV format 由注册校验拒绝，运行期同样校验。两个节点与 D1 API 共用纯 Java 调用组件，保持 mock 门禁、固定错误与输出复验一致。

**句柄生命周期**：随机 UUID，绑定 runId，仅存进程内存，不接受路径、URL、data URI 或 base64；TTL 为 5 分钟，最多 64 条、总字节最多 32MiB，容量不足固定失败且不驱逐其他运行。过期、未知、跨运行句柄使用固定错误，不回显原值。执行引擎通过纯 Java 运行资源生命周期端口，在每个 execute/resume 执行段开始开放资源，在终态、挂起、异常（含租约失效）后释放；30 秒定时扫描兜底。provider 调用前后及句柄/变量写入前检查取消和执行权，已关闭运行拒绝迟到写入。重试仍由原引擎控制；挂起时音频被释放，下游若依赖挂起前句柄则固定失败，必须在恢复段重新合成。

**入口边界**：控制台上传/播放直接走 D1 同步 API；用 `audio-roundtrip` 示例的 `start → tts.speak → asr.transcribe → end` 证明真实节点传递，只返回文本 transcript。此示例可通过既有文本会话触发，音频由领取任务的 worker 在当前执行段生成和消费；不把本机句柄作为持久异步任务输入。外部 WAV 上传直接驱动任意工作流、跨实例音频传输、终态音频下载和音频持久化另立契约。

**控制台**：独立音频面板，分别显示 ASR/TTS 能力和 mock 提示（模拟转写、静音 WAV）；File/FormData 与 Blob URL，不编码 base64、不自动填聊天框、不写浏览器持久存储、不记录正文。新结果/清空/页面退出释放 Blob URL；中止或隔离旧请求回包。能力关闭时禁用对应按钮。

**验收**：纯 Java 测试覆盖共享门禁/复验、句柄 owner/TTL/容量/副本、节点参数/失败/取消；执行测试直接抓 Recorder 全部快照、检查点与事件，确保无音频字节或 DTO，验证终态/挂起/异常回收、重试/fallback、超时迟到写入。H2 验证实际 node_runs/workflow_runs JSON 仅文本/句柄/元数据；回归 D1 API、能力、错误、执行与审批。串行全量（本地 Redis 集成）和离线打包，真实 PG/Redis 栈浏览器手验音频成功/错误/清空/通道关闭和零 JS 异常。日志与独立包归档 D2，不继承 C 负载结论。

## 4. 接口与测试边界

核心端口保持纯 Java、无 Spring/数据库依赖。D0 以合成 WAV、mock provider 契约与 Spring 装配/能力接口测试验收；默认关闭、通道关闭、非 mock 模式不能装配 mock 或报告可用。异常仅保留固定 Reason，DTO 的 toString 不带音频/转写/合成文本，字节采用防御性复制。厂商协议确认后再使用本地 JDK `HttpServer` 桩验证 HTTP 请求/响应；真实厂商按单样本 runbook 执行。

语音计费按秒/字符单独记录，不换算为 token，也不接入现有 token 额度，除非另立契约。所有压测继续强制 mock。

## 5. 批准边界

D0、D1 已验收；用户已批准开始 D2 节点/控制台，按上述范围实施。真实厂商联调另行确认；数据库迁移、音频持久化和真实密钥均不在本方案内。

## 6. D0/D1 证据目录

日志、独立 JAR 与脱敏 JSON 快照统一保存在 `target/m4-3d/`，使用独立文件名，不覆盖 C 的归档包或负载证据。快照记录测试状态、源码/日志/JAR 路径与 SHA256，不复制音频正文、厂商原文、凭证或 Surefire 的运行环境属性。D0 与 D1 验收结论分别保存在 `docs/m4-3d-validation.md`、`docs/m4-3d-d1-validation.md`；D1 未重跑 C 的 Gatling 场景，不继承其负载验证归属。
