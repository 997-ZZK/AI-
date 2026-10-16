# 文档入库错误边界

## 目标

文档上传链路会接触 PDFBox、图片解码、OCR provider、Multipart 读取和 MinIO/S3 SDK。它们的原始异常正文、URL、凭证片段、文件路径和异常链属于内部诊断，不能进入 HTTP 响应、`documents.error`、运行记录或普通 WARN 日志。

## 固定契约

- PDF、图片解析失败统一返回固定的 `文档解析失败：文件无法解析`；不保留底层 cause 或 suppressed。
- 自定义 OCR provider 的 `available()` / `recognize()` 抛出任意运行时异常时，统一返回固定的 `OCR 调用失败：视觉识别请求未完成`；能力缺失仍保留固定的 OCR 不可用提示。
- OCR 通过固定 `reason` 区分开关关闭、能力缺失、页数限制、空识别和调用失败；开关关闭/能力缺失保留 503，其他 OCR 拒绝保留既有 400，不再按 message 文案猜测状态码。
- OCR 开关关闭、OCR 页数超限、空文件、文件过大和不支持格式继续使用原有可操作提示，但不拼接原始异常正文或未净化文件名。
- Multipart 文件读取失败统一返回 `文件读取失败`；响应和日志不包含底层 IOException message。
- JSON 解析失败统一返回 `JSON 解析失败：内容不是合法 JSON`；索引流水线只按切片、向量化和索引写入三个阶段存储固定失败原因，不将 provider、向量存储或数据库的原始异常写入 `documents.error`。
- 对象存储写入、元信息查询、读取、建桶/连通性失败分别使用固定分类；异常不带 cause。原件留存失败仍不阻断文档入库。
- 下载时对象存储未启用返回 503；已记录 object key 但对象不存在返回 404；stat 或 open 的存储故障返回 503，覆盖 `stat → open` 竞态。
- 原件下载继续流式返回。响应提交之后的读取/关闭失败转换为无原始异常链的固定 IOException；已经提交的响应只能中止传输，无法再改成 404/503 JSON。
- 对象键只使用已净化的文件名，日志只保留稳定标识；原始文件名中的控制字符、换行和路径分隔符不透传到响应头或日志。
- 下载在打开流之前校验元数据：负数长度视为存储故障；空值、非法值、控制字符或通配 MIME 回退为 `application/octet-stream`，合法 MIME 只保留 type/subtype。

## 范围

本切片只收紧 `DocumentParser`、`DocumentExtractor`、`DocumentController`、`DocumentIngestService` 的上传/错误转译和 `engine/storage` 的异常边界。文件名将控制字符和路径分隔符替换为下划线，最多 200 字符并保留短扩展名；`kbId` 拒绝控制字符与路径分隔符。不修改 PDF/OCR 正常流程、对象存储协议、schema、配置默认值或真实厂商调用。

测试与验收文件沿用现有目录：`api` / `ingest` / `integration` 放离线边界回归；日志、独立 JAR 与统计/哈希快照使用 `target/ops/document-ingest-20261015-*` 前缀。若 Windows 系统临时目录无法收尾，测试可将 JVM `java.io.tmpdir` 指向 `target/ops/document-ingest-20261015-temp/`，只改变本次测试进程的临时路径，保留 JUnit 原有清理规则，不跳过测试或关闭清理；不清理旧产物。
