# 文档入库错误边界验收（2026-10-15）

## 结果

实现契约见 `document-ingest-error-boundary.md`。本轮将 PDF/图片、JSON、Multipart 读取、可插拔 OCR 和对象存储异常收口为固定诊断；文档索引失败按切片、向量化和索引写入阶段记录，不再持久化底层异常正文。

- 最终全量：1089 条，0 失败，0 错误，6 条按既有开关跳过；Maven `BUILD SUCCESS`。6 条为 Redis Stream 真实设施集成 5 条、真实 embedding 校准 1 条。
- 全量中的受影响 9 套件合计 59 条，0 失败/错误/跳过；其中本轮新增 26 条。首轮独立定向 51 条也通过，旧日志保留。
- 离线打包：`-DskipTests package` 成功，独立包为 `target/ops/document-ingest-20261015-aiflow-engine.jar`；哈希与套件统计见 `target/ops/document-ingest-20261015-verification.json`。
- 全量测试 JVM 的 `java.io.tmpdir` 指向项目可写目录 `target/ops/document-ingest-20261015-temp/`。此前系统临时目录的 39 个 Windows `@TempDir` 清理错误本轮未复现；没有排除类、关闭清理或改系统设置。编译阶段仍出现既有 `.m2` JAR `ZipFS AccessDeniedException` 关闭诊断，但本轮测试和打包均成功。

## 验证范围

- `DocumentExtractorTest` / `VisionOcrProviderTest`：15 + 3 条，PDF 文本层优先、扫描页 OCR、开关/页数/空文本、无效图片头、自定义 provider 异常和异常链脱敏。
- `DocumentIngestErrorBoundaryTest`：5 条，上传读取、JSON、文档失败记录、FAILED 保存再次失败及留存旁路日志脱敏。
- `DocumentOriginalStorageIntegrationTest`：13 条，原件逐字节下载、留存失败旁路、禁用、stat/open 故障和竞态、流读取/关闭失败、旧对象键头部与元数据校验。
- `MinioStorageErrorBoundaryTest`：5 条，直接桩化 SDK 的建桶、写入、查询和读取失败，验证固定分类和异常链清理。
- `DocumentOcrIntegrationTest` / `RagApiIntegrationTest` / `KnowledgeBaseReindexIntegrationTest` / `ErrorResponseIntegrationTest`：4 + 4 + 3 + 7 条，原解析、页码、引用、检索、重建和 HTTP 错误语义回归。

## 环境与边界

仅使用项目现有 JDK 21、Maven 3.9.16、本地依赖缓存、H2、mock 和合成数据。没有真实厂商流量、真实凭证读取、付费模型调用、schema/配置/依赖/CI 修改或旧产物删除。

原件保持流式下载；响应提交后的网络读取失败会中止传输，只保留固定诊断，无法再切换成 404/503 JSON。数据库不可用时可能无法持久化 FAILED，此时记录固定 WARN 并返回固定失败，不宣称状态已保存。

审查另确认 `StorageProperties.timeout` 尚未接入 MinIO 客户端；本切片不改超时策略，这项列入后续独立修复。真实 MinIO 与厂商能力联调仍后置。

## 复现命令

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
$maven='D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd'
$options=@('-o','-s','target/m4-3a-maven-settings.xml',
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository',
  '-Dmaven.compiler.fork=true')
# 定向
& $maven @options '-Dtest=DocumentExtractorTest,VisionOcrProviderTest,DocumentIngestErrorBoundaryTest,DocumentOriginalStorageIntegrationTest,MinioStorageErrorBoundaryTest,DocumentOcrIntegrationTest,RagApiIntegrationTest,ErrorResponseIntegrationTest,KnowledgeBaseReindexIntegrationTest' test
# 全量：临时路径仅对本次测试 JVM 生效，JUnit 清理照常执行
New-Item -ItemType Directory -Path target/ops/document-ingest-20261015-temp -Force | Out-Null
& $maven @options '-DargLine=-Djava.io.tmpdir=D:\work1\AIFlowEngine\target\ops\document-ingest-20261015-temp' test
# 离线打包
& $maven @options '-DskipTests' package
```

日志与独立 JAR、统计/哈希快照使用 `target/ops/document-ingest-20261015-*` 文件名；首轮定向结果另保留，后续结果不得覆盖旧报告。
