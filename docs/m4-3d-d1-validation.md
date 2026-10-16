# M4-3D D1 验证记录

2026-10-13：**D1 mock 同步音频 API 已完成并验收**。本切片把 D0 的厂商无关 mock ASR/TTS 端口接入两个同步 HTTP 接口；未接入真实厂商、工作流节点、控制台或音频持久化。

## 交付范围

- `POST /api/v1/audio/transcriptions`：multipart `file`，支持 `audio/wav`、`audio/x-wav`、`audio/wave`，返回统一 JSON `ApiResponse`，model 只取配置。
- `POST /api/v1/audio/speech`：JSON `text`、可选 `voice`/`format`，只接受 WAV，直接返回原始音频；设置 `Content-Length`、`Cache-Control: no-store`、固定 `speech.wav` 和受控音频元数据头。
- 输入按文件声明大小和有效上限 + 1 byte 读取，校验 RIFF/WAV、PCM 参数、完整帧、大小和时长；文本按 Unicode 码点限制。
- `text`、`voice`、`format` 只接受 JSON 字符串；数字、布尔、对象和数组返回 400，不改变全局 Jackson 标量转换。
- TTS 输出复验 MIME、WAV、大小、时长、provider 和 model；无效上游输出为 502。provider 异常按固定原因映射 502/503/504，响应和日志不保留原异常、正文或厂商原文。
- API 与 `/api/v1/capabilities` 使用同一门禁：全局/通道启用、mock mode、配置 provider 为 mock、实际 bean id 为 mock 且 `available()` 为真。探测异常固定为不可用。
- Servlet multipart 真实服务器验证覆盖 10MiB 文件上限、12MiB 请求上限、应用 5MiB 音频上限、附件/文档既有 400 契约和请求临时文件清理。应用不写音频原件，不进入 AttachmentStore、会话/记忆、运行快照或事件。

## 验证结果

使用 JDK 21.0.12.1+1、Maven 3.9.16 和已有本地依赖缓存，所有命令从项目根目录离线串行执行。定向回归覆盖音频测试、能力报告、错误响应、SSE、附件和文档接口：

- **192 条，0 失败、0 错误、0 跳过**；日志：`target/m4-3d/d1-targeted-20261013-run4.log`。
- 真实嵌入 Servlet multipart：**4 条，0 失败、0 错误、0 跳过**；日志：`target/m4-3d/d1-multipart-final3.log`。
- 全量回归（`-Daiflow.redis.integration=true`）：**803 条，0 失败、0 错误、1 条预期跳过**；日志：`target/m4-3d/d1-full-20261013-run2.log`。Redis 集成 5 条全部执行；唯一跳过是未启用真实 embedding 模式的 `RagRealEmbeddingCalibrationTest.calibratesThresholdAgainstRealEmbeddingService`。
- 离线 `-DskipTests package`：`BUILD SUCCESS`；日志：`target/m4-3d/d1-package-20261013-run1.log`。

定向首轮曾因 Java `HttpClient` 在 Tomcat 超限后继续写请求体而收到连接关闭（保留在 `d1-targeted-20261013-run3.log`）；测试随后改为只发送声明超限的原始 HTTP 头并读取容器返回的 413，最终验证通过。生产限制与既有附件/文档状态码未被放宽。

## 交付物与边界

- 独立 JAR：`target/m4-3d/aiflow-engine-audio-d1-803.jar`
  - SHA256：`1A0761B8F02574724AD22A0B9C750F39A8ABBC69C67F56FEEDCCFBB7775FB2A8`
- 脱敏快照：`target/m4-3d/d1-delivery-20261013.json`
  - 记录测试汇总、套件结果、产物/日志/源码路径和 SHA256；不保存音频、转写/合成正文、请求体、运行属性、厂商原文或凭证。

本轮未读取真实百炼 key、未访问真实厂商、未修改 `.env` 或数据库 schema、未运行 Gatling。D2 的 `asr.transcribe` / `tts.speak` 节点、控制台和真实厂商适配仍待后续单独确认。
