# Embedding 与探活错误边界验收

状态：2026-10-14，离线验收已完成。范围和设计见 `embedding-probe-error-boundary.md`。

## 改动

`HttpEmbeddingProvider` 对非 2xx 使用固定状态错误，跳过默认错误正文诊断；网络、消息转换和非法请求头使用固定分类且不携带原始 cause/suppressed。缺失或空向量的既有校验保留，响应中的 null 条目按空向量拒绝。正常批量向量、UTF-8、模型字段、Bearer 头、模型身份和维度不变。

`EndpointProbe` 将请求构造和 Authorization 校验纳入固定错误边界；网络失败不再使用原始异常 message。模型缺失诊断只保留本地配置的 expectedModel，不回显上游 `/models` 返回的模型 id 列表。401/403、5xx、合法空清单、未知清单、前后缀匹配、404 可达和成功恢复语义不变。

## 验证

```powershell
. .\script\dev-env.ps1
mvn -o -s target\m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-Dtest=EmbeddingErrorBoundaryTest,ProbeErrorBoundaryTest,EndpointProbeTest,TieredEmbeddingProviderTest,EndpointHealthTest,CapabilityApiIntegrationTest,ErrorResponseIntegrationTest' test
mvn -o -s target\m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests=false' test
mvn -o -s target\m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests' package
```

| 检查 | 结果 | 证据（`target/ops/`） |
| --- | --- | --- |
| 定向协议/分级/能力/错误响应 | 66 条，0 失败/错误/跳过 | `embedding-probe-20261014-targeted.log` |
| 全量离线回归 | 142 套件、1054 条，0 失败/错误、6 跳过 | `embedding-probe-20261014-tests.log` 与 Surefire XML |
| 离线打包 | BUILD SUCCESS | `embedding-probe-20261014-package.log` |
| 独立交付包 | 已保存，保留旧阶段包 | `embedding-probe-20261014-engine.jar` |
| 脱敏交付快照 | 仅路径、大小、SHA256、范围与测试统计 | `embedding-probe-20261014-delivery.json` |

覆盖内容包括：

- embedding 400/401/429/500/302、错误 JSON、缺失/空/null 向量、条数不一致、批量与 UTF-8、无 key、空输入、网络/转换/非法 header，以及真实 HTTP 分级成功/全失败；健康状态、WARN、最终异常堆栈不含合成 marker。
- 探活缺失模型时健康状态、DEBUG 和 `CapabilityRegistry` 公开候选不含上游模型 id；401/403/503 错误正文、非法 header、拒连、网络异常和中断固定化；成功清零恢复、未知/空清单和旧状态判定回归。

新增 `EmbeddingErrorBoundaryTest` 21 条、`ProbeErrorBoundaryTest` 14 条，共 35 条；原探活测试改为明确拒绝模型列表回显。全量包含上一轮对话错误边界、运行回放、上下文/工作流/RAG 黄金集、审批、记忆/画像与音频回归。6 条跳过延续既有开关：5 条真实 Redis 集成和 1 条真实 embedding 校准；本轮不重新声明设施容量或真实模型质量。

独立代码审查未发现阻断：确认只有两个生产类修改，HTTP 状态处理在正文转换前执行；Spring 关闭响应可能排空字节，但不会将错误正文或原始异常挂到向上的异常图中。正常向量、探活状态和重定向工厂设置保留。

独立 JAR SHA256：`5e937bf96a346ef7b3ead4b471fe58a4478cc14cbc47c2ee93709dd2b4a50070`。测试与构建完成后无遗留 Java 进程。

## 交付边界

本轮只用本地 JDK HTTP 桩、Mockito 和合成标记，无真实厂商访问、凭证读取或模型额度消耗。没有改检索算法、ANN/索引、阈值、维度、schema/迁移、配置、控制台、依赖、CI/CD 或系统设置；因此不重跑 ANN 性能与真实 embedding 校准，不新增浏览器验收。

仅限制这两个适配器生成的诊断。原有重定向策略、构造器中的启动配置错误、正常业务正文、其他自定义 provider 和已存旧记录不在本轮脱敏承诺中。旧产物保留，未删除文件/数据或执行 push/rebase；项目仍无首个提交，Git diff 不足以证明完整变更，交付快照另与上轮基线逐项比较。
