# 第一层离线 Provider / HTTP 验收

状态：**2026-10-14 第一层已完成离线验收**。定向测试 85 条全部通过；最终全量发现 903 条，实际执行 897 条，0 失败、0 错误、6 条按开关跳过；离线打包成功。只证明厂商无关接口与本地协议边界，真实厂商联调和模型质量仍待后续验证。

## 验证范围

按 `provider-layer1-plan.md` 实现云知识库 JSON 映射/HTTP 传输与 ASR/TTS HTTP 协议 SPI/桥接。仅使用 loopback JDK HttpServer、合成 WAV、JSON 和合成凭证，不连接真实厂商。

第一层定向测试 85 条已通过：云 KB HTTP 20、JSON/配置绑定 16、音频 HTTP 29、音频桥接 15、相邻合并 5。新增合并边界验证把插入换行计入字符上限。

## 已实现的边界

- 云 KB：`KnowledgeBaseProtocol` 解耦字段映射和传输；默认自定义 `/retrieve` 契约、可配置 JSON Pointer、必需字段和有限原始分数校验、缺序号保留 `-1`、缺 hash 计算 SHA-256。分数归一化继续走已有 normalizer。
- 音频：`AudioHttpProtocol`、受控请求/响应、可替换 transport、ASR/TTS 桥接，复验 WAV 与受控元数据；静态能力探测不发请求、不解析凭证；异步任务/WebSocket 不当作同步协议执行。
- HTTP：JDK HttpClient，不跟随重定向；请求/累计响应限长、总 deadline 覆盖 body、并发限额、中断取消、默认不重试；显式安全协议才有限重试，出现 Retry-After 停止自动重试。音频不自动重试连接异常或超时；云 KB 仅在显式安全配置下允许重试这些错误。
- 凭证：仅配置环境变量名、实际调用时解析；本地测试注入合成值。旧云 KB Java 构造兼容、Spring record 构造绑定已验证；旧内联 api-key 不静默用于调用，迁移说明见 `cloud-kb-protocol.md`。
- 错误：仅固定原因和受控状态，不保留可能包含 URL/正文/凭证的底层 cause；请求/响应诊断字符串不打印二进制、正文或凭证。

新抽象没有注册真实音频 Spring bean 或新增 mode，D1/D2 的 mock 门禁及临时句柄生命周期继续由存量回归验证。本轮未改 ANN 查询/索引/性能参数，因此不运行 ANN 真实栈基准；未改控制台，不重复浏览器验收。

## 全量回归中的测试夹具修正

首轮全量 902 条出现 1 项失败：`ProfileIntegrationTest.timezoneEditPreservesStartButResetAndReenableExcludeOldRuns` 在首次画像查询时计数为 0，尚未执行时区变更。该夹具用第二次 `Instant.now()` 创建记录，未控制数据库微秒精度与即时统计窗口边界；现有报告没有记录落库运行时间，不能仅凭报告断言是哪侧越界。

验证修正约定：用数据库读回的 `statisticsSince` 创建该合成运行，并断言读回运行时间等于下界且落在响应窗口内，明确验证闭区间下界包含语义；原有运行计数、状态计数、时区编辑不重置起点、重置/重新启用排除旧记录的断言全部保留。不改画像业务逻辑，不添加 sleep 或重试断言，不降低门禁。

首轮日志保留在 `target/m4-3d/layer1-20261014-tests.log`。沙箱读取已有 Maven 缓存 JAR 时的 AccessDenied 诊断保留在 `layer1-20261014-compile-diagnostic.log`，随后在获准的本机执行环境中离线编译、测试。

## 最终验证与交付

使用已有 JDK 21.0.12.1 / Maven 3.9.16 / 本地依赖缓存，无新增依赖和在线下载。在项目根目录执行：

```powershell
. .\script\dev-env.ps1
mvn -o -s target/m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-Dtest=HttpKnowledgeBaseProviderTest,JsonKnowledgeBaseProtocolTest,JdkAudioHttpTransportTest,HttpAudioProviderTest,AdjacentMergerTest' test
mvn -o -s target/m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests=false' test
mvn -o -s target/m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests' package
```

| 验证 | 结果 | 证据（`target/m4-3d/`） |
| --- | --- | --- |
| 第一层定向 | 85 条，失败/错误/跳过均为 0 | `layer1-20261014-targeted.log` |
| 最终全量 | 133 个套件、903 条，0 失败、0 错误、6 跳过 | `layer1-20261014-tests-final.log` |
| 离线打包 | BUILD SUCCESS | `layer1-20261014-package.log` |
| 独立交付包 | 94,366,005 bytes | `layer1-20261014-engine.jar` / `.jar.sha256` |
| 脱敏快照 | 仅套件统计、路径与 SHA256，不复制环境属性或正文 | `layer1-20261014-delivery.json` |

独立 JAR SHA256：`40d84c3d80262d9c27cfd0ed5af45caab9a5f7f07496238a4e5604d524004d08`。

跳过的 6 条为 5 条需显式开启的真实 Redis 集成测试（`aiflow.redis.integration=true`）和 1 条真实 embedding 校准（`aiflow.eval.embedding.mode=http`）。本轮没有重跑外部设施或真实模型验收；既有 Redis/ANN 容量结论仍以各阶段验收为准。

全量包含 D1 音频 API/关闭态/上传/错误映射、D2 工作流与生命周期、画像夹具修正、RAG pipeline/黄金集等回归，均通过。`git diff --check` 无输出；由于项目尚无首个提交且源码整体未跟踪，该命令不构成本轮完整差异证据，交付快照另保存源码/资源/文档哈希以便核对。

本轮未连接真实厂商、未产生付费模型调用；测试仅使用合成凭证。不修改 `.env`、真实密钥、CI/CD、数据库 schema/迁移或系统配置，不删除已有产物，不 push/rebase。没有新增音频存储、下载入口或真实音频模式。

## 完成后的下一步

第二层是合成数据与提示词/对话黄金集，以及确定性错误/降级回归：先定义场景、预期和门禁，再覆盖会话/记忆/画像上下文、RAG 引用与拒答、提示注入和超时/取消/限额等分支；使用可控 mock 与本地桩，生成离线结果报告。提示词只参考聆思项目通用组织和评测方法，不复制私有业务正文。

离线门禁能证明接口和上下文/降级契约，不能证明真实模型回答、转写或发音质量。后续拿到 API/模型时再做厂商激活层：核对官方协议、装配 provider、扩展 `AudioOperations` / `CapabilityRegistry` 门禁、验证小样本与真实分数量程。不能宣称届时仅改配置即可，但引擎执行核心和工作流契约无需随厂商重写。
