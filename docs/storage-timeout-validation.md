# 对象存储超时验收（2026-10-15）

## 结果

实现契约见 `storage-timeout-design.md`。本轮把已有 `aiflow.storage.timeout` 接入 MinIO 的连接、写入、读取和单次 HTTP call 总时限；`open()` 返回后的响应体读取仍受该 call 时限约束。MinIO 默认 HTTP/1.1、TLS/自定义 CA 和固定异常边界保持不变。

- 新增 `MinioStorageTimeoutTest` 19 条，0 失败/错误：正常 put/stat/open、四项超时装配、合法/非法 `Duration` 边界、stat/open/查桶/建桶/写入慢响应头、下载响应体停滞与持续滴流。
- 定向回归 78 条，0 失败/错误/跳过；包含原件下载、入库错误边界、OCR、RAG、HTTP 错误响应和重建回归。
- 全量离线测试 1108 条，0 失败、0 错误、6 条按既有开关跳过；跳过项为 Redis Stream 真实设施 5 条和真实 embedding 校准 1 条。测试 JVM 使用 `target/ops/storage-timeout-20261015-temp/`，没有关闭 JUnit 清理。
- 离线打包成功：`target/ops/storage-timeout-20261015-aiflow-engine.jar`，SHA256 `364a9b486b7901c709b3848f366c474aad4f90a0c7594b357da8c50df42ba7e1`。

统计和日志见 `target/ops/storage-timeout-20261015-verification.json`、`storage-timeout-20261015-targeted.log`、`storage-timeout-20261015-full.log`、`storage-timeout-20261015-package.log`。

## 实现口径

- 通过 MinIO SDK 的 `HttpUtils.newDefaultHttpClient` 保留默认 HTTP/TLS 行为，再配置 OkHttp `callTimeout`；四项 timeout 使用同一毫秒值。
- `timeout` 必须位于 `1ms` 到 `2147483647ms`（含边界），有效值的毫秒以下尾数截去；非法值抛固定 `IllegalArgumentException`，不回显配置值。
- 超时分类继续映射为 `BUCKET`、`WRITE`、`STAT`、`READ`，不保留 SDK cause/suppressed；原件写失败仍不阻断文档入库。
- region 查询、查桶、建桶和分片上传可能包含多个 HTTP call，各自计时；这不是整个 `put/stat/open` 的统一 deadline。dispatcher 排队、查桶锁等待和调用方本地处理不计入该保证。

## 环境与边界

仅使用项目现有 JDK 21、Maven 3.9.16、本地依赖缓存、JDK loopback HTTP server、H2、mock 和合成凭证。没有真实 MinIO 或厂商流量、真实凭证读取、付费模型调用、依赖/schema/配置/CI 变更或旧产物删除。真实 MinIO 慢网络与厂商联调仍后置。

## 复现命令

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
$maven='D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd'
$options=@('-o','-s','target/m4-3a-maven-settings.xml',
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository',
  '-Dmaven.compiler.fork=true')
New-Item -ItemType Directory -Path target/ops/storage-timeout-20261015-temp -Force | Out-Null
& $maven @options '-DargLine=-Djava.io.tmpdir=D:\work1\AIFlowEngine\target\ops\storage-timeout-20261015-temp' '-Dtest=MinioStorageTimeoutTest,MinioStorageErrorBoundaryTest,DocumentOriginalStorageIntegrationTest,DocumentIngestErrorBoundaryTest,DocumentExtractorTest,VisionOcrProviderTest,DocumentOcrIntegrationTest,RagApiIntegrationTest,ErrorResponseIntegrationTest,KnowledgeBaseReindexIntegrationTest' test
& $maven @options '-DargLine=-Djava.io.tmpdir=D:\work1\AIFlowEngine\target\ops\storage-timeout-20261015-temp' test
& $maven @options '-DskipTests' package
```
