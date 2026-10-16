# 音频 HTTP 适配边界（离线第一层）

状态：2026-10-14，用户批准厂商无关抽象；本文件先定义契约，再实现代码。此切片不代表真实百炼、OpenAI 或其他厂商已经接入，也不更改 D1/D2 的默认关闭、mock 装配与调用门禁。

## 分层与配置

- `audio/AsrProvider`、`TtsProvider` 和现有 DTO 继续作为引擎端口。
- `integration/audio/AudioHttpProtocol<Q,S>` 只承担受控请求编码、成功响应解码；实际厂商后续各自实现，不能从聊天 endpoint 推断语音路径或字段。
- `HttpAsrProvider`、`HttpTtsProvider` 连接协议 SPI 与传输 SPI；只支持同步短音频。异步任务和 WebSocket 可声明协议种类，但静态能力为不可用，调用固定失败。
- `AudioHttpTransport` 可换成测试传输；`JdkAudioHttpTransport` 使用已有 JDK HTTP client，无新增依赖。
- 不注册新 Spring bean、不新增可激活的音频 mode、不读取凭证值、不修改 `.env`。未来激活真实 provider 时须另行确认配置、协议、鉴权和单样本验收。
- `AudioHttpCredentials` 仅保存环境变量名与显式注入的解析器，不缓存值；构造和能力探测不解析。协议编码调用时才取头；缺失、无效或解析失败固定 `UNAVAILABLE`。本轮仅使用返回合成值的测试解析器，不提供或调用系统环境默认解析器。

## 受控传输与错误

请求只有可信适配器提供的固定 endpoint、Content-Type/Accept、显式 headers、body 和安全重试声明。endpoint 禁止 userinfo、fragment、query；远程只接受 HTTPS，本地 HTTP 仅允许字面量 loopback（测试协议桩），不跟随重定向。此边界不是面向用户开放的任意 URL 代理；厂商签名 URL、代理或网络白名单需要单独扩展契约。

请求/响应字节采用防御性复制；`toString()` 只包含大小/状态，不包含 URI、头值、正文或文本。不保留外部异常 cause、不记录厂商报文。transport 限制请求字节、累计响应字节和整次调用总时长（含响应 body、重试等待）。本地并发默认最多 8 次（可配置 1..64），满载立即固定 `UNAVAILABLE`，不建立等待队列。中断恢复线程标志并停止调用，不继续重试；close 主动中止连接，不等待未结束的 body。

默认只发一次请求。只有协议明确声明该请求可安全重试（例如厂商确认幂等键）且 transport 显式配置多次尝试时，429/502/503/504 才可有限重试；不自动重试连接异常、超时、401/403、其他 4xx、无效报文或非幂等合成。不得把未知失败重发解释为“不会重复计费”。

响应存在 `Retry-After` 时一律停止自动重试，交由调用方后续明确策略；不忽略它提前重发、不解析厂商提供的任意长等待。没有该头时只采用本地有界间隔，总 deadline 仍生效。

401/403/其他 4xx 和 429 映射现有 `REJECTED`；超时为 `TIMEOUT`；重定向、202 异步结果、无效/超限成功响应为 `INVALID_RESPONSE`；其他传输/5xx 为 `CALL_FAILED`。沿用 D1 的 502/503/504 Web 映射，本切片不改变公共错误接口。provider 包装器会复验请求及成功输出的 WAV、媒体类型、长度和时长；解析异常仅转为固定无效响应错误。

## 能力与验证

`available()` 只检查协议是否为同步及静态装配有效，不发送探活 HTTP，不读取账户额度，不表示已获厂商权限或模型质量达标。D1/D2 仍只报告和使用 mock。

本地 JDK `HttpServer` 契约测试使用合成 WAV、固定 JSON 和纯内存假凭证，验证 multipart/JSON 编码映射、二进制解码、固定错误、限长、重试、总超时、取消、重定向拒绝和脱敏。示例路径与字段只属于测试 fixture，不宣称兼容任何厂商。音频不落会话、附件、记忆、队列、数据库或验证报告；只在请求内存中使用。
