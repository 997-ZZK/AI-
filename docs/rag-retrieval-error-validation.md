# RAG 检索错误边界验收（2026-10-14）

## 结论

已完成 `RagRetrievalService` 的最小错误边界收紧。可插拔 embedding 与检索来源抛出的原始 message、`toString()`、cause 和 suppressed 不再进入 `RetrievalException`、检索节点失败结果或 WARN 日志；来源健康检查异常按部分降级处理。正常向量化、来源融合、阈值、后处理和提示词组装语义保持不变。

## 验证范围

- `RagRetrievalErrorBoundaryTest`：5 条，覆盖 embedding 调用异常、embedding `dimensions()` 异常、来源 `healthy()` 异常、来源 `retrieve()` 异常、cause/suppressed 脱敏、日志脱敏和部分降级。
- `RetrievalNodeExecutorTest`：5 条，包含 provider 异常不进入节点失败结果的回归。
- `RagRetrievalServiceTest`、RAG 后处理、向量存储、ANN、本地 provider、云知识库协议和 RAG 提示词黄金集相关套件：合计 130 条，0 失败，0 错误，0 跳过。
- 全量 `mvn test`：1063 条，0 失败，39 个错误，6 个跳过。39 个错误均发生在 Windows `@TempDir` 扩展收尾清理阶段，异常为 `C:\Users\99730\AppData\Local\Temp\junit-*` 的 `AccessDeniedException`；测试主体断言未报告失败。
- 使用项目现有 JDK 21、Maven 3.9.16、本地依赖缓存和离线模式；未访问真实厂商、未读取真实凭证、未修改 schema、配置、依赖、索引或性能参数。

## 命令

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -o `
  -s target/m4-3a-maven-settings.xml `
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository' `
  '-Dmaven.compiler.fork=true' `
  '-Dtest=RagRetrievalServiceTest,RagRetrievalErrorBoundaryTest,RetrievalNodeExecutorTest,' +
  'ContextPromptAssemblerTest,RetrievalPipelineTest,AdjacentMergerTest,VectorsTest,RetrievedChunkTest,' +
  'MockEmbeddingProviderTest,TieredEmbeddingProviderTest,LocalRetrievalProviderAnnTest,' +
  'PgVectorEmbeddingStoreAnnQueryTest,InMemoryEmbeddingStoreTest,HttpKnowledgeBaseProviderTest,' +
  'JsonKnowledgeBaseProtocolTest,RagPromptGoldenSetTest' test
```

离线打包沿用同一 JDK、Maven 设置和缓存执行 `-DskipTests package`。本轮没有真实网络或付费模型调用。全量测试结果已在本轮复现；其中的 Windows `@TempDir` 清理权限问题属于测试环境收尾问题，需在具备清理权限的环境单独处理。
