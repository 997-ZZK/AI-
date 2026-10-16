# Embedding 与探活错误边界

状态：2026-10-14 已实现并完成离线验收，见 `embedding-probe-error-validation.md`。延续对话错误边界的后续修复，用户已通过“继续”授权推进。

## 问题与范围

`HttpEmbeddingProvider` 把 RestClient 原始异常及 cause 向上抛出；它们可能含 HTTP 错误正文、请求 URL 或解析报文。`TieredEmbeddingProvider` 会把 message 复制到健康状态、档位状态与日志。`EndpointProbe` 的网络异常 message 和缺失模型时的上游模型清单也会进入公开能力诊断。

生产范围仅 `integration/HttpEmbeddingProvider.java` 与 `integration/EndpointProbe.java`，沿用现有目录；不改分级算法或状态接口。测试位于既有 `src/test/java/com/aiflow/engine/integration`；新增 `EmbeddingErrorBoundaryTest` / `ProbeErrorBoundaryTest`，更新原探活测试的清单回显断言。日志、独立包和仅含路径/哈希/统计的交付记录使用既有 `target/ops/embedding-probe-20261014-*` 前缀，旧文件保留。

## 契约

- Embedding 对实际收到的非 2xx 响应先执行自定义状态处理，只报告固定说明和数值 HTTP 状态，不解析、保留或传播错误正文，跳过默认错误正文收集。底层 HTTP 实现在关闭响应时可能排空连接；本切片约束诊断信息，不承诺网络层一字节不读，不改变既有重定向策略。
- Embedding 网络、JSON/消息转换及请求构造失败使用固定分类，不附带原始 cause/suppressed。正常向量、条数/空向量校验、模型身份与维度声明不变；响应中的 null 条目按空向量拒绝，避免暴露 NPE 实现诊断。
- 探活请求构造（含非法 Authorization）与网络异常使用固定分类，只记录一次失败；中断仍恢复线程中断标记并按原契约记录“探活被中断”。
- 模型缺失诊断保留本地配置的 expectedModel，移除上游模型 id 列表。配置的模型标识本来就是能力报告的一部分；上游返回的清单内容不应成为额外诊断正文。
- 探活原有判定不变：401/403 不可用、其他非 5xx 按原契约可达；合法空清单不可用，未知清单不判死；模型前后缀匹配不变。成功恢复会清零失败信息。
- 这里只限制上述适配器生成的诊断，不改变正常业务输入/输出，不宣称任意自定义 provider 的错误都已脱敏。

## 验证与排除项

只用本地 JDK HTTP 桩、Spring 测试设施和合成标记，覆盖 HTTP 错误正文、异常链、JSON 转换、非法 header、网络失败、清单回显、探活成功恢复，以及真实 HTTP embedding 经分级后的健康状态/日志/最终失败。保留成功请求、批量向量、条数/空向量与探活旧语义回归。

先定向回归协议/档位/健康状态/能力接口/错误响应，再全量离线测试和打包。无真实厂商访问、付费额度或新依赖。检索查询、ANN/索引、阈值、向量维度/量纲均不改，不属于检索性能改动；不重做 ANN 性能或真实模型校准。控制台、schema/迁移、配置、密钥、CI/CD 和系统配置均不改，不清理历史数据。
