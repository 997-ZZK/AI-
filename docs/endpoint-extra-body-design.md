# 厂商扩展参数解析边界

## 问题与范围

`EndpointExtraBody` 在启动时解析厂商私有字段。原实现将 Jackson message/cause 拼入异常，可能回显配置正文；JSON `null` 或顶层字段值为 null 会触发空指针，默认读取还可能忽略对象后多余的 JSON 值。

本轮只修改 `integration/EndpointExtraBody` 的解析与错误转译，以及既有 `integration` 测试。无需改动实际 YAML、凭证、厂商协议、HTTP 传输、路由或数据库。

## 契约

- Java `null`、空串、纯空白仍表示未配置，返回空 Map。
- 非空配置必须恰好包含一个 JSON 对象，允许尾随空白；数组、字符串、数字、布尔、JSON 字面量 `null`、畸形 JSON 和对象后的额外值均拒绝。
- 顶层 null 字段仍不支持，但由固定配置错误替代空指针；嵌套 null、对象、数组、布尔和数字保持既有透传行为。
- `model` / `messages` / `stream` 仍不得覆盖，错误只包含代码内固定字段名，不带配置中的值。
- JSON/类型/读取失败统一为固定 `IllegalArgumentException("extra-body 不是合法 JSON 对象")`，无原始 cause/suppressed，不拼接字段名或值。顶层 null 值使用固定 `extra-body 顶层字段不能为 null`。
- 严格尾随值校验只作用于本次 `ObjectReader`，不修改共享 `ObjectMapper`。重复键与其他 JSON 兼容开关不在本轮范围。
- 返回 Map 保持不可修改，正常 `thinking` 等字段合并顺序与协议主干护栏不变。

## 目录与验证

先更新本设计和 AGENTS，再修改代码。扩展既有 `src/test/java/com/aiflow/engine/integration/EndpointExtraBodyTest.java` 与 `OpenAiCompatibleChatProviderTest.java`；使用合成正文及本地 HTTP 桩，验证固定异常、异常图/堆栈不含标记、非对象/null/尾随值、共享 mapper 不受影响和正常请求字段。

根代理串行执行 Maven，定向覆盖解析、chat 协议/错误/路由及黄金集，再运行全量离线测试与打包。日志、独立 JAR、统计/哈希快照放既有 `target/ops/endpoint-extra-body-20261015-*`；测试 JVM 临时目录用同前缀 `-temp/`，JUnit 清理照常执行，不删除旧产物。不读取真实凭证，不访问真实厂商或付费模型，不改依赖/schema/实际配置/CI。

本切片已完成离线验收，结果见 `endpoint-extra-body-validation.md`。流式对话响应体的完整超时保障另立切片，本轮不混入。
