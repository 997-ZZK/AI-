# 对话 HTTP 错误边界验收

状态：2026-10-14 已完成。范围和契约见 `chat-error-boundary.md`。

## 结果与改动

`OpenAiCompatibleChatProvider` 不再读取 HTTP 错误正文，非 2xx（包含 3xx）只报告数值状态码；SSE error、请求序列化/构造、网络及流读取失败只报告固定分类。异常不保留原始 cause/suppressed，非法 JSON 的 DEBUG 日志不包含解析报文。错误响应流均关闭，关闭失败不覆盖已有错误；正常读完后单独关闭失败仍是固定 provider 失败，不伪装成功。

正常请求内容、usage、模型覆盖、图像格式、SSE 增量、用户取消与路由顺序保持原契约。增量前失败可换档，已有增量后失败停止本次换档。发送前的序列化/请求构造失败不改健康状态；HTTP/流内/网络/流读取失败只记一次。

生产只修改 `src/main/java/com/aiflow/engine/integration/OpenAiCompatibleChatProvider.java`。既有协议测试反转了“错误正文必须可见”的旧断言，并新增 SSE error 回归；两个新测试文件为 `ChatErrorBoundaryTest` 与 `ChatErrorRoutingTest`。本轮共新增 21 条执行用例。

## 验证

JDK 21、Maven 3.9.16，复用本机依赖缓存，命令均离线：

```powershell
. .\script\dev-env.ps1
mvn -o -s target\m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=OpenAiCompatibleChatProviderTest,ChatErrorBoundaryTest,ChatErrorRoutingTest,ProviderRouterTest' test
mvn -o -s target\m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests=false' test
mvn -o -s target\m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests' package
```

| 检查 | 结果 | 证据（`target/ops/`） |
| --- | --- | --- |
| 定向协议/路由 | 33 条，0 失败/错误/跳过 | `chat-errors-20261014-targeted-retry02.log` |
| 全量离线回归 | 140 套件、1019 条，0 失败/错误，6 跳过 | `chat-errors-20261014-tests.log` 与 Surefire XML |
| 离线打包 | BUILD SUCCESS | `chat-errors-20261014-package.log` |
| 独立交付包 | 已保存，未覆盖旧阶段包 | `chat-errors-20261014-engine.jar` |
| 交付快照 | 仅路径、大小、SHA256、测试统计与范围比较 | `chat-errors-20261014-delivery.json` |

新边界测试 16 条覆盖 302/400/401/429/500、流 error 的 message/code/type、真实 DEBUG 日志、序列化及非法 header、真实本地固定长度截断流与拒连，以及确定性的网络/读取/关闭异常。对外异常、完整堆栈、健康状态和日志均检查合成敏感标记；错误正文禁止迭代、关闭次数与单次失败计数有断言。

新路由测试 4 条走真实 HTTP 适配器和 `ProviderRouter`：HTTP/SSE 增量前失败可降级；增量后失败禁止换档；所有候选失败。另经真实 `LlmNodeExecutor` 验证 `degradedReasons` 与节点失败 error 不含上游标记。其余 9 条既有协议回归与 4 条路由回归全部通过。全量包含错误响应、回放、审批、记忆/画像、音频和黄金集回归。

6 条跳过延续既有开关：5 条真实 Redis 集成和 1 条真实 embedding 校准。本轮没有重新声明外部设施容量或模型质量。第一次定向编译受沙箱缓存读取限制（commons-logging），第二次受 Hibernate annotations 缓存限制；只扩展本地缓存/测试临时文件访问后通过，失败日志 `targeted.log` / `targeted-retry01.log` 保留，未跳过或绕过测试。

独立 JAR SHA256：`3221f0909f55effbc1cb4ba88afdad01bbbccd2f016bf2660e556676cf3b3980`。

独立代码审查未发现阻断问题：确认原始消息及异常链不传播、关闭失败不会变成假成功，回调和取消路径未被宽泛捕获。本轮未改控制台，无需新增浏览器验收。

## 范围限制与后续

只收紧对话调用适配器生成的诊断。正常用户/模型内容、其他适配器与端点探活不包含在本次脱敏交付声明中；已有运行和日志不回写清理。没有新增或修改凭证、应用配置、schema/迁移、依赖、CI/CD 或系统配置，没有 push/rebase 或删除文件/数据。

测试只用本地桩与合成标记，无真实厂商调用或模型额度消耗。下一项可沿同一错误边界检查 embedding 与探活，再等待用户准备真实模型时做小样本厂商激活验收。完整账号/归属隔离与本地 ASR/TTS 仍按原路线后置。
