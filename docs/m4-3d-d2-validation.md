# M4-3D D2 验证记录

2026-10-13：**D2 工作流节点与控制台已完成验收**。本切片在 D1 mock 同步 API 之上增加 `tts.speak` / `asr.transcribe` 节点、运行内临时音频句柄，以及独立的一次性音频控制台面板。真实厂商联调、音频持久化和数据库 schema 变更仍未实施。

## 交付范围

- `tts.speak` 和 `asr.transcribe` 注册为内置工作流节点，节点只传字符串或受控 `audioHandle`，不把 `byte[]` / base64 放入 `NodeContext`、运行快照、`node_runs` 或事件。
- 音频句柄为随机 UUID，绑定 `runId` 和执行 attempt，仅存进程内存；TTL 5 分钟，最多 64 条、总字节最多 32MiB，30 秒扫描兜底。终态、挂起、异常、取消、超时和租约失效都会释放资源；迟到的旧 attempt 不能回写新执行段。
- 节点与 D1 API 共用 mock 门禁、输入校验、WAV 复验和固定脱敏错误；句柄写入或变量写入失败时立即清理，避免失败尝试占用容量。
- 控制台新增独立 ASR/TTS 面板：WAV 上传、mock 转写、TTS 合成、播放和能力状态。请求使用 `FormData` / `Blob`，不编码 base64，不调用会话/附件 API，不写浏览器持久化；AbortController、generation 栅栏和 Blob URL 回收隔离旧请求。
- 示例工作流为 `src/main/resources/workflows/audio-roundtrip.json`（`start → tts.speak → asr.transcribe → end`）。

## 自动化验证

使用 JDK 21.0.12.1+1、Maven 3.9.16 和已有本地依赖缓存，从项目根目录离线串行执行：

- **D2 定向套件：180 条，0 失败、0 错误、0 跳过**；日志：`target/m4-3d/d2-targeted-20261013-final.log`。
- **执行引擎相关回归：33 条，0 失败、0 错误**；日志：`target/m4-3d/d2-engine-latest-20261013.log`。
- **临时目录/资源生命周期补充套件：60 条，0 失败、0 错误、0 跳过**；日志：`target/m4-3d/d2-temp-suite-20261013.log`。
- **全量回归（含本地 Redis 集成）：818 条，0 失败、0 错误、1 条预期跳过**；日志：`target/m4-3d/d2-full-20261013-final-latest.log`。唯一跳过是未启用真实 embedding 模式的校准测试。
- 离线 `-DskipTests package`：`BUILD SUCCESS`；日志：`target/m4-3d/d2-package-20261013-final.log`。

自动化覆盖节点参数/失败/取消、句柄 owner/TTL/容量/副本、终态/挂起/异常回收、重试与 fallback、超时迟到写入、快照和事件脱敏，以及 D1 API/能力/错误/执行回归。

## 真实栈浏览器验证

真实栈使用 PostgreSQL 16.15，Flyway schema version 10，无迁移；音频配置为 mock，未读取真实百炼 key，也未访问真实厂商。

- `http://localhost:18080/console.html` 加载成功，能力面板显示 `ASR mock` / `TTS mock`。
- 选择合成 WAV 后点击“转写”，页面显示 mock provider/model 和时长元数据；结果只停留在当前页面。
- 点击“清空音频面板”后，ASR 文本、元数据和 TTS 播放器均消失，Blob URL 已释放。
- 以进程级临时配置关闭 `AIFLOW_AUDIO_ASR_ENABLED` 与 `AIFLOW_AUDIO_TTS_ENABLED` 后刷新页面，能力表显示两个通道 `unavailable`，且“转写”和“合成 WAV”按钮均为 disabled。
- 浏览器 console 收集到 **0 条 error、0 条 warn**。

浏览器栈日志：`target/m4-3d/d2-browser-engine-final.out.log`、`target/m4-3d/d2-browser-disabled.out.log`。

## 交付物与边界

- 独立 JAR：`target/m4-3d/aiflow-engine-audio-d2-818.jar`
  - SHA256：`9A29004C68D9A96C96E3906769036C9EF1B81EB2DB8F04C245CD5F735F521308`
- 脱敏快照：`target/m4-3d/d2-delivery-20261013.json`
  - 只记录测试状态、文件路径和 SHA256；不保存音频、转写/合成正文、请求体、厂商原文或凭证。

本轮没有修改 `.env`、密钥、数据库 schema 或持久化模型，没有运行真实模型压测。验证结束后已停止真实栈进程。
