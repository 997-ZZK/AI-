# 厂商扩展参数解析验收（2026-10-15）

## 结果

实现契约见 `endpoint-extra-body-design.md`。启动时解析 `extra-body` 的错误不再包含 Jackson 原始 message/cause 或配置正文；JSON null、顶层 null 和尾随内容均按固定规则拒绝。

- 定向 129 条，0 失败、0 错误、0 跳过。`EndpointExtraBodyTest` 24 条、`OpenAiCompatibleChatProviderTest` 11 条；相较上一验收分别新增 20 + 2 条。
- 其余定向包含 `ChatErrorBoundaryTest` 16、`ChatErrorRoutingTest` 4、`ProviderRouterTierOrderTest` 4、上下文/工作流/RAG 黄金集 24/21/18、`ErrorResponseIntegrationTest` 7。
- 全量离线测试 1130 条，0 失败、0 错误、6 条按既有开关跳过；跳过项为 Redis Stream 真实设施 5 条和真实 embedding 校准 1 条。测试 JVM 临时目录位于 `target/ops/endpoint-extra-body-20261015-temp/`，没有关闭 JUnit 清理或排除测试。
- 离线打包成功，独立 JAR 为 `target/ops/endpoint-extra-body-20261015-aiflow-engine.jar`，SHA256 `6eb15dffe303a08a60388999fd4a1e98e95da865250a6b9478feabbf9ab4bffe`。

产物前缀为 `target/ops/endpoint-extra-body-20261015-*`：`-targeted.log`、`-full.log`、`-package.log`、`-verification.json`。快照只保留统计、路径和哈希，不复制 Surefire 中的环境属性。

## 验证内容

- 畸形 JSON、非对象根、JSON null 和尾随值：固定 `extra-body 不是合法 JSON 对象`，异常 message、toString、cause、suppressed 和打印堆栈均不含合成私有标记。
- 顶层 null 值：固定 `extra-body 顶层字段不能为 null`，不再抛空指针，不回显任意字段名。
- 保留字段 `model/messages/stream`：固定字段提示，拒绝配置中的覆盖值。
- Java null、空白、单个对象及尾随空白：保持原语义；返回 Map 顶层不可修改。
- 正常嵌套 null、数组、布尔、数值和厂商参数继续透传；本地 HTTP 桩确认发出的请求主干和私有字段均正确。
- 读取器运行时失败也只保留固定诊断；严格尾随校验不修改共享 `ObjectMapper`。
- provider 构造期拒绝非法参数，不发 HTTP 请求，不降低端点健康状态。

## 环境与边界

仅使用已有 JDK 21、Maven 3.9.16、本地依赖缓存、H2、mock、本地 HTTP 桩和合成数据。没有真实凭证读取、真实厂商或付费模型流量、实际 YAML/依赖/schema/CI 变更或旧产物删除。生产修改仅 `integration/EndpointExtraBody`，没有改变正常 SSE、HTTP 传输或路由。

审查确认下一项为 chat 流式响应体的整次超时与取消分类：现有请求超时在响应头后不再限制 `ofLines()` 读取，读取中的中断可能被算作端点故障。引擎已有节点时限，本轮不声称整个引擎会无限挂起；该问题后续单独实施和验收。

## 复现命令

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
$maven='D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd'
$options=@('-o','-s','target/m4-3a-maven-settings.xml',
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository',
  '-Dmaven.compiler.fork=true')
New-Item -ItemType Directory -Path target/ops/endpoint-extra-body-20261015-temp -Force | Out-Null
& $maven @options '-DargLine=-Djava.io.tmpdir=D:\work1\AIFlowEngine\target\ops\endpoint-extra-body-20261015-temp' '-Dtest=EndpointExtraBodyTest,OpenAiCompatibleChatProviderTest,ChatErrorBoundaryTest,ChatErrorRoutingTest,ProviderRouterTierOrderTest,ContextGoldenSetTest,WorkflowGoldenSetTest,RagPromptGoldenSetTest,ErrorResponseIntegrationTest' test
& $maven @options '-DargLine=-Djava.io.tmpdir=D:\work1\AIFlowEngine\target\ops\endpoint-extra-body-20261015-temp' test
& $maven @options '-DskipTests' package
```
