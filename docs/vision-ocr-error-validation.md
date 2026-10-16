# Vision OCR 错误边界验收（2026-10-14）

## 结论

已完成 `VisionOcrProvider` 的最小错误边界收紧。路由调用失败统一转换为固定 `OCR 调用失败：视觉识别请求未完成`，不设置 cause 或 suppressed 异常；能力缺失仍返回固定「OCR 不可用」提示。成功转写和 `NO_TEXT` 语义保持不变。

## 验证范围

- `VisionOcrProviderTest`：3 条，覆盖上游异常正文/cause/suppressed 脱敏、能力缺失、成功文本和 `NO_TEXT`。
- `HttpKnowledgeBaseProviderTest`、`JsonKnowledgeBaseProtocolTest`：36 条既有知识库 HTTP/协议边界回归继续通过；本轮确认无需生产修改。
- 定向合计：39 条，0 失败，0 错误，0 跳过。
- 扩大回归：排除 8 个受 Windows 临时目录清理权限影响的既有 `@TempDir` 测试类后，988 条，0 失败，0 错误，6 条按开关跳过。
- 全量尝试：1057 条，0 失败，39 错误，6 条跳过；39 条均为既有 `@TempDir` 扩展上下文关闭时删除 `C:\Users\99730\AppData\Local\Temp\junit-*` 目录被拒绝，未涉及本轮代码路径。使用项目 JDK 21、Maven 离线模式和本地 H2；未访问厂商、未读取真实凭证。
- 离线打包：`mvn -o -q -Dmaven.compiler.fork=true -DskipTests package` 成功。

## 边界与后续

本轮只覆盖 `VisionOcrProvider` 的外部路由异常，不扩展到 PDFBox 解析、上传读取、对象存储或任意自定义 `OcrProvider` 的底层异常；这些路径另列后续切片。真实视觉模型质量与厂商协议仍需拿到账号和协议后联调。
