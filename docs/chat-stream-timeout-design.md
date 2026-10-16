# Chat 流式响应超时与取消契约（已验收）

## 问题与范围

`OpenAiCompatibleChatProvider` 使用 JDK `HttpClient` 的 `BodyHandlers.ofLines()`。现有 `HttpRequest.timeout` 只覆盖请求建立和响应头；响应头返回后，流式响应体的逐行读取可以继续等待。读取中的线程中断还会被统一包装成 provider 故障，导致用户取消降低端点健康度。

本切片只修改 `integration/OpenAiCompatibleChatProvider` 及既有 integration 测试。路由、执行引擎、SSE API、模型协议、配置字段和数据库保持不变；沿用已有 `Endpoint.timeoutMs`。

## 超时语义

- `timeoutMs` 表示一次 chat HTTP call 从发起到收到 `[DONE]` 或兼容的正常 EOF 的单调时钟总截止时间，包含连接、响应头和流式响应体读取。
- 响应头返回后，为当前响应流安排一次可取消的截止关闭；计时器只关闭这一条流，不中断或影响调用线程和其他请求。超时关闭即使表现为 EOF，也必须被识别为固定的 `chat 流读取超时`，不能把已经收到的部分内容当成成功。
- 正常在截止时间前收到 `[DONE]` 或 EOF 保持现有成功语义；成功后取消计时任务。超时和读取故障只记录一次健康失败，使用固定诊断，不保留原始 cause/suppressed。
- 这是单次 HTTP call 的截止时间，不承诺覆盖 provider 排队、调用方回调执行或整个工作流节点；节点自身仍有执行时限。

## 取消语义

- 发送阶段或响应体读取阶段检测到当前线程中断都抛 `RunCanceledException`，恢复中断标志，不记录端点故障，不触发路由换档。
- 循环和成功提交前再次检查中断，覆盖已经缓冲的 SSE 行；已有增量后仍沿用“失败不换档”的路由规则。
- 计时器关闭流不依赖中断调用线程，避免把超时误报为用户取消；读取异常只有在当前线程确实中断时才按取消处理。

## 验证范围

扩展既有 `OpenAiCompatibleChatProviderTest` 与 `ChatErrorBoundaryTest`，使用绑定 `127.0.0.1` 的 JDK HTTP 桩和合成数据，覆盖响应头后停滞、持续小片段超过总截止时间、截止前正常 `[DONE]`、首个增量后线程中断、健康状态与增量边界、计时任务释放和固定异常。定向 109 条、全量 1134 条（0 失败、0 错误、6 条按开关跳过）及离线打包通过，结果见 `chat-stream-timeout-validation.md`；产物使用 `target/ops/chat-stream-timeout-20261016-*`，测试临时目录同前缀，旧产物保留。

不访问真实厂商、不读取真实凭证、不调用付费模型，不修改依赖、配置、schema、CI/CD 或控制台。真实厂商的流式行为仍需后续在用户提供 API/额度后用单样本验证。
