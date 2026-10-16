# 第一层：离线 Provider 与 HTTP 协议边界

状态：2026-10-14 用户批准实施，现已完成离线验收，结果见 `provider-layer1-validation.md`。前置为 D0/D1/D2 已验收，见 `m4-3d-d2-validation.md`。本轮不依赖真实 API 额度；真实厂商激活与质量验收后置。

## 范围与落位

1. 保留现有纯 Java `AsrProvider`、`TtsProvider`、`RetrievalProvider` 核心端口；引擎、工作流及控制台不依赖厂商字段。
2. `integration/audio` 增加程序装配的同步 HTTP 请求/响应、协议映射 SPI、HTTP 传输、ASR/TTS 桥接 provider。协议编码可以生成受控 JSON 或 multipart；未知协议、WebSocket 与异步任务不得被当作同步协议猜测执行。
3. 云知识库在 `integration` 将 HTTP 传输和 JSON 字段映射分开；保留旧字段映射作为显式本地示例，允许替换 codec 或使用受限 JSON Pointer 映射，不执行配置脚本。不把示例 `/retrieve` 当作百炼等真实厂商协议。
4. HTTP 传输配置包括连接/整次调用超时、请求/响应字节上限、并发上限、相对路径和有限重试策略；不跟随重定向。默认单次请求，显式允许重复的协议才能重试；取消/中断不重试；有 Retry-After 时不得早于服务端要求重试，可直接停止自动重试交给调用方。状态码、格式错误、响应超限和超时使用固定原因，不挂带有请求/响应/地址/凭证原文的 cause。
5. 凭证边界只保留环境变量名和可注入解析器；测试只注入合成值，不读本机真实凭证。配置/DTO 的字符串表示不输出正文、二进制或凭证。探测可用性只表示静态配置支持，不发请求，也不保证真实模型可调用。
6. 云知识库严格验证分数和字段类型，缺失切片序号保留未知；相邻合并按 source/doc 隔离且未知顺序禁止合并，新增换行也计入合并长度上限。归一化仍由现有 source 对应 normalizer 完成，不改 Embedding/ANN 参数或阈值。

代码与协议契约继续在已规范的 `integration` / `integration/audio` / `retrieval/post` / 对应测试目录；细节由 `audio-http-protocol.md`、`cloud-kb-protocol.md` 约束。验收记录放 `provider-layer1-validation.md`，日志/JAR/脱敏快照使用既有 `target/m4-3d/layer1-20261014-*` 文件名，不清理已有产物。

## 兼容与本轮排除项

音频默认关闭、现有 mock 门禁、D1 错误 JSON 和 D2 临时句柄生命周期保持当前契约。新 HTTP 音频桥接通过程序装配及本地协议桩验证，应用不新增可误激活真实调用的 mode/bean。真实厂商 adapter 激活后才扩展共享能力门禁并重新验收；本轮不把 HTTP 桩的通过状态显示为真实可用。

云知识库旧 HTTP 字段默认值保持可读，但缺失 `chunk_index` 不再伪造顺序；此为纠正引用合并行为。认证仅经注入的凭证解析器读取环境变量名；遗留内联凭证配置不得静默使用或打印，兼容/迁移行为写入协议文档。

本轮不修改 `.env`、密钥/token、CI/CD、schema、数据库数据或系统配置；不删除文件、不 push/rebase、不新增全局依赖；不做音频持久化、下载、账户权限、声纹、本地模型部署或真实付费调用。无需接企业 VPN/代理即可运行本地桩。聆思项目仅只读参考可通用的分层/超时/评测方法，不复制企业凭证、业务数据或私有提示词正文。

## 验收

- 本地 JDK `HttpServer` 桩验证 JSON/multipart 编码、认证注入、响应解析、响应上限、超时、中断、状态分类、重试次数、重定向拒绝以及正文/凭证脱敏。
- 纯测试验证未知协议静态不可用、关闭态不调用 provider、不解析凭证、DTO 防御复制、未知序号与不同 source 不相邻合并。
- 回归 D1/D2 音频/API/能力/错误/执行和 RAG/黄金集。若未触及 ANN 查询与性能参数，记录这一范围；若实际触及则按 AGENTS 跑两份 ANN 真实栈脚本。
- 离线全量测试、打包、独立 JAR 与 SHA256；快照只记录套件统计、文件路径和哈希，不复制 Surefire 环境属性。
- 完成后明确第二层入口：合成数据和对话/提示词黄金集、确定性错误/降级回归；真实模型质量、真实厂商协议验收仍等待之后的 API/模型。

## 只读设计参考

本轮只参考已有工程的通用方法，不复制实现或私有报文：`LSAgentFrameworkHost/sdk-ts/src/http/jsonClient.ts` 的总超时/响应限长/取消；`LSAgentFrameworkHost/cmd/listenai/templates/listenai-voice-chat/src/knowledge/listenaiKnowledgeProvider.ts` 的传输与字段提取分离；`LLMRequestService/src/services/agentRuntime/agentRuntimeModelInfrastructure.ts` 的环境变量名配置与付费流式调用不重试原则。参考内容由本地只读源码核对，不需要企业服务或账号。
