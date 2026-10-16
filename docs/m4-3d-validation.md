# M4-3D D0 验证记录

2026-10-13：**D0 已交付并验收；整个 M4-3D 尚未完成**。本次包含厂商无关的 ASR/TTS 端口、PCM WAV 校验、mock 实现、独立配置和能力报告。D1 同步音频 API、D2 工作流节点/控制台与真实厂商适配后续分别确认，方案见 [`m4-3d-plan.md`](m4-3d-plan.md)。

## 交付与边界

- `engine/audio` 保持纯 Java：`AsrProvider` / `TtsProvider`、同步请求/响应、`AudioLimits`、`WavPcmValidator` 与脱敏 provider 异常；byte[] 在输入/输出处防御性复制，DTO 的 `toString` 隐藏音频、转写/合成文本及任意元数据。
- `integration/audio` 实现离线 mock。ASR 返回明确标注的模拟结果；TTS 生成确定性的静音 WAV，固定 16kHz/单声道/16bit、100..2000ms，`voice` 保留但不影响输出。未实现识别或真实语音合成质量。
- 默认 WAV 未压缩 PCM、单声道、16kHz、16bit、≤30 秒、≤5MiB；合成文本≤2000 个 Unicode 码点。校验 RIFF 精确总长、chunk 边界/奇数 padding、唯一 fmt/data、非空完整采样帧、byteRate/blockAlign，允许合法 JUNK；时长向上取整到毫秒后判限。
- 本地 PCM 配置支持 1..192000Hz、1/2 声道、8/16/24/32bit，输入须匹配所配参数；时长至少 1ms，转换毫秒不得溢出。mock TTS 固定格式不受配置改变，不兼容时报告不可用。
- `aiflow.audio.*` 默认关闭，与聊天路由/图片附件存储独立；根开关、模式、provider 和通道开关共同决定 mock 装配。凭证只保存合法环境变量名，D0 不读取其值。
- `GET /api/v1/capabilities` 新增 `asr`/`tts`，依据实际 bean 与 `available()` 报告 `mock` 或 `unavailable`，保持既有 L0-L3 计算。mock ASR 至少要容纳一个完整 PCM 帧；mock TTS 至少要容纳固定格式的 100ms/3244bytes。可用状态只代表最小样本可行，不保证每个请求通过限制。
- 未新增数据库迁移、音频 API、节点、控制台、真实 HTTP 或音频持久化；音频不进入会话/记忆、AttachmentStore、运行快照或事件。本轮未启动临时引擎，也未执行真实语音调用或 Gatling。

## 验证结果

使用现有 JDK 21.0.12.1+1、Maven 3.9.16 和依赖缓存，离线串行执行。新测试共 **52 条**：

| 测试类 | 条数 | 核心断言 |
| --- | ---: | --- |
| `WavPcmValidatorTest` | 17 | 合法 WAV/JUNK、长度/chunk/padding、fmt/data 唯一性、编码/PCM 参数、大小/时长/帧边界 |
| `AudioDtoTest` | 7 | 防御复制、toString 脱敏、Unicode 码点与空值 |
| `AudioLimitsTest` | 3 | 不支持参数、亚毫秒时长、Duration 溢出 |
| `MockAudioProviderTest` | 11 | 模拟转写、确定性静音、输出复验、自定义模型、最小可用性 |
| `AudioConfigTest` | 13 | 默认关闭、独立开关、非 mock、配置绑定、能力报告与实际装配一致 |
| `AudioCapabilityApiIntegrationTest` | 1 | mock 开启态能力接口与既有档位一致 |

既有 `CapabilityApiIntegrationTest` 增补关闭态语音断言。定向执行以上测试与该既有用例：**53 条、0 失败、0 错误、0 跳过**。

全量：**116 个测试类，687 条、0 失败、0 错误、1 跳过**，`BUILD SUCCESS`，耗时 1 分 59 秒。启用 `-Daiflow.redis.integration=true`；`RedisRunEventBusIntegrationTest` 的 5 条全部执行并通过。唯一跳过为 `RagRealEmbeddingCalibrationTest.calibratesThresholdAgainstRealEmbeddingService`，未设置真实 embedding 校准开关 `aiflow.eval.embedding.mode`，不作为真实模型验证。

随后离线 `-DskipTests package`：**BUILD SUCCESS**，耗时 6.268 秒；此步只打包，不重复宣称执行测试。JAR 内容已核对包含 D0 核心、mock 与装配类。

默认沙盒首次定向编译无法读取已有 `.m2` 依赖，保留失败日志；改用获准的本机缓存读取权限后，定向、全量与打包均通过。没有安装依赖、删除缓存或改测试绕过失败。

## 命令与证据

以下命令均从项目根目录执行，日志写 `target/m4-3d/`：

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
# 定向：新增 52 条 + 既有能力接口 1 条
& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -o `
  -s target/m4-3a-maven-settings.xml `
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository' `
  '-Dtest=WavPcmValidatorTest,AudioDtoTest,AudioLimitsTest,MockAudioProviderTest,AudioConfigTest,AudioCapabilityApiIntegrationTest,CapabilityApiIntegrationTest' test
# 全量，含本地 Redis 集成
& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -o `
  -s target/m4-3a-maven-settings.xml `
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository' `
  '-Daiflow.redis.integration=true' test
# 已通过全量后打包
& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -o `
  -s target/m4-3a-maven-settings.xml `
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository' -DskipTests package
```

- 首次沙盒失败日志：`target/m4-3d/d0-targeted-sandbox-20261013.log`。
- 定向成功日志：`target/m4-3d/d0-targeted-20261013-run1.log`。
- 全量成功日志：`target/m4-3d/d0-full-20261013.log`。
- 打包成功日志：`target/m4-3d/d0-package-20261013-run1.log`。
- 脱敏交付快照：`target/m4-3d/d0-delivery-20261013.json`，记录每个测试类/用例的状态、源码和证据文件 SHA256，不保存 Surefire 运行环境属性、正文或凭证。项目当前没有 Git commit，源码未跟踪，不能用空 `git diff` 代替交付证据。
- 独立归档包：`target/m4-3d/aiflow-engine-audio-d0-687.jar`，94,274,611bytes；SHA256：`D5534E830F4F10338C454D17DB0CCDC6E98ABE469A3B25BCA7D092C6C2A97B09`。

能力 HTTP 验证使用 Spring 集成测试/MockMvc，装配矩阵使用 ApplicationContextRunner；未声称在独立部署实例上验过音频 API。C 的 635 诊断包及其负载结果保持原归属，D0 包没有重跑 C 的负载场景。
