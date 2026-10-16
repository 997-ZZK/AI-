# 对象存储超时契约

## 问题与范围

`StorageProperties.timeout` 已有默认值 `10s`，但 `MinioObjectStorage` 没有将它传给客户端，运行时使用的是 SDK 默认超时。本切片接入现有配置，不新增属性、依赖或数据库变更。

生产修改限于 `storage/MinioObjectStorage` 的 HTTP 客户端构造与配置校验，以及 `StorageProperties` 的语义说明。保留 MinIO 默认 HTTP/1.1 与证书处理，通过 SDK 的 `HttpUtils.newDefaultHttpClient` 设置连接、写入、读取超时，再设置 OkHttp `callTimeout`，注入 MinIO builder。

## 超时语义

- `aiflow.storage.timeout` 默认仍为 `10s`，同一个值用于连接、写入、读取和单次 HTTP call 总时限。
- call 时限从 HTTP call 开始实际执行起计算，包含连接、请求发送、响应头等待和响应体读取。`open()` 返回后，响应体尚未读完时仍受该时限约束；缓慢而持续的下载也可能因总时限结束而中止。已经缓冲的字节可能继续可读，不承诺到点后立即拒绝所有读取。
- SDK 的 region 查询、查桶、建桶和分片上传可能产生多个 HTTP call，每个 call 独立计时。它不是整个 `put/stat/open` 操作的统一 deadline；查桶锁等待、OkHttp dispatcher 排队不计入，调用方本地处理和系统 DNS 也不保证一定能在该时限内停止。
- SDK 内部的 HTTP 重试/重定向共享所属 call 的时限；SDK 重新发起的 call 各自计时。不新增应用层重试。
- 构造启用的 MinIO 适配器时，timeout 必须非空，且位于 `1ms` 到 `2147483647ms`（含边界）；不足 1ms、零、负数或过大值用固定 `IllegalArgumentException` 拒绝，不回显配置值。有效值中不足一毫秒的尾数截去，避免 Duration 转换溢出；小于 1ms 的值不会被截成零后意外禁用超时。
- 未开启或缺少凭证时继续走既有未开启实现，不因未使用的 timeout 创建客户端。

## 错误边界

建桶、写入、stat 和 open 阶段的超时沿用固定 `BUCKET/WRITE/STAT/READ` 分类，不保留 SDK cause/suppressed。原件写失败仍允许文档入库为 `INDEXED`。返回流后的 IOException 继续由下载接口的 `SafeOriginalStream` 清理；响应提交后中断传输，不能再改发 JSON 错误。

## 目录与验证

新增离线 HTTP 桩测试放既有 `src/test/java/com/aiflow/engine/api/MinioStorageTimeoutTest.java`，只绑定 `127.0.0.1` 的临时端口，使用合成凭证。覆盖正常上传/查询/下载、慢响应头、慢响应体、持续滴流、查桶/建桶/写入超时与非法配置。连接/写入时限的数值装配可另做定点断言，不以不稳定的外部黑洞地址验证。

根代理串行运行 Maven，定向回归包括超时测试、原件下载、SDK 异常边界、文档旁路、OCR/RAG 与错误响应，再运行全量离线测试与打包。产物使用既有 `target/ops/storage-timeout-20261015-*` 前缀；测试 JVM 临时目录放 `target/ops/storage-timeout-20261015-temp/`，保留 JUnit 清理。独立 JAR、日志及只含统计/哈希的快照保留旧产物，不访问真实 MinIO 或厂商、不读取真实凭证、不调用付费模型。

验收结果在实际验证后写入 `storage-timeout-validation.md`。
