# Chat 流式超时与取消验收

日期：2026-10-16

## 结果

`OpenAiCompatibleChatProvider` 现在把已有端点 `timeoutMs` 解释为从请求发起到 `[DONE]` 或兼容 EOF 的单调时钟总截止时间。响应头返回后由 daemon 计时器只关闭当前响应流；超时固定报告 `chat 流读取超时`，读取中的线程中断报告 `RunCanceledException`，不降低端点健康状态，也不把原始异常链带入诊断。

## 覆盖

- 响应头返回后无正文，按固定流读取超时结束并记录一次 provider 故障。
- 持续小片段输出仍受总截止时间约束，不因每片数据到达而无限延长。
- 截止前收到 `[DONE]` 成功，计时任务取消，后续请求不受旧任务影响。
- 首个增量已交付后再中断，保留已交付增量，抛 `RunCanceledException`，保留线程中断标志，端点仍为 `up`。
- 既有 SSE、错误边界、路由档位顺序、上下文/工作流/RAG 黄金集和 API 错误响应回归。

## 验证命令与结果

使用 JDK 21、Maven 3.9.16、本地 Maven 仓库和 `target/m4-3a-maven-settings.xml` 离线执行；测试临时目录为 `target/ops/chat-stream-timeout-20261016-temp`。

- 定向回归：109 条通过，0 失败，0 错误。
- 全量离线回归：1134 条执行，0 失败，0 错误，6 条按开关跳过，`BUILD SUCCESS`。
- 离线打包：`mvn -o -s target/m4-3a-maven-settings.xml -DskipTests package`，`BUILD SUCCESS`。
- 独立产物：`target/ops/chat-stream-timeout-20261016-aiflow-engine.jar`
- SHA-256：`8a0268bf54a2aee4d64dccad32972215e7c6327e95d461afb78f2b88170c3aea`

## 边界

本轮只使用绑定 `127.0.0.1` 的 JDK HTTP 桩和合成请求，不访问真实厂商、不读取真实凭证、不调用付费模型。真实厂商的 SSE 心跳、代理缓冲和断链行为仍需在取得 API/额度后做单样本联调；本轮契约不覆盖调用方 delta 回调执行时间或整个工作流节点预算。
