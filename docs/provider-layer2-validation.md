# 第二层离线黄金集验收

状态：**2026-10-14 第二层已完成离线验收**。63 条黄金场景与 9 条设施测试全部通过；全量 975 条，实际执行 969 条，0 失败、0 错误、6 条按开关跳过；离线打包成功。范围与门禁见 `provider-layer2-plan.md`。

## 本层交付

新增三份 `schemaVersion=1`、`goldenVersion=20261014-v1` 的合成黄金集。静态夹具先定义独立预期，实际调用生产装配、路由、节点、执行和 RAG 后处理代码；可控端口只注入合成记忆、固定回复和故障，不把回复本身当模型质量分数。

| 黄金集 | 条数 | 类别与主要验证 |
| --- | --- | --- |
| 上下文 | 24 | 装配 4、开关 5、读取失败 5、角色边界 1、画像策略 6、预览 3；逐条核对实际请求角色/全文/顺序、provider/model、四端口读取、召回 query/hits 标记与 token 扣减 |
| 工作流 | 21 | 路由 6、额度 3、安全 7、重试 2、超时 1、取消 1、生命周期 1；状态/固定错误原因、独立输出、调用/扣费、事件顺序与终态、每个 node run 收口及生命周期端口成对收口 |
| RAG 提示词 | 18 | 拒答 3、引用 3、来源/顺序 4、提示边界 8；真实 pipeline、独立编号/标签/正文、来源/页码映射、完整规则句、分隔符唯一性与原件不变 |

三组均逐 case 生成 JUnit 动态用例，报告位于 `target/m4-3d/layer2-20261014-{context,workflow,rag}-report.json`。夹具、加载器和测试分别放 `src/test/resources/eval/layer2-*-golden.json` 与 `src/test/java/com/aiflow/engine/eval/`。

上下文预览限定默认 inherit、`${input}` 节点，先将真实请求与独立预期逐字段核对，再验证预览与该请求一致；不声称预览支持任意节点模板、model 或参数覆盖。没有修改生产记忆/画像装配口径，本层由存量集成回归验证这些路径。

工作流 18 条走真实 `ExecutionEngine → LlmNodeExecutor → ProviderRouter`，另 3 条走真实 `SessionMessageService.submit`，验证输入拒绝时仓储、附件、调度与运行协调器零交互。超时/取消使用 latch 控制顺序，验证晚到 DELTA 被 attempt fence 拒绝；部分流失败核对精确片段与一次推送。重试每个 attempt、取消节点和运行终态均需恰好收口一次。

## 门禁自身的验证

另有 9 条评测设施测试：拒绝空集、重复 id、缺类别/预期、错误版本、未知字段/类别、重复 JSON 字段、尾随 JSON、错误字段类型；漏跑生成 NOT_RUN 并使门禁失败。重复执行（含并发重复）永久记为 FAIL，首次执行完成不能覆盖违规结果。

报告只保留套件/黄金集版本、资源 SHA256、case id/类别、PASS/FAIL/NOT_RUN、受控异常类名与数值统计，不保存输入、预期、实际正文、异常 message/cause、请求或环境属性。此约定针对新评测报告；既有 provider 通用异常处理没有在本层重构，不能据此宣称全项目日志均已脱敏。

## 暴露并修复的生产缺陷

唯一生产改动为 `retrieval/ContextPromptAssembler.java`。旧的单次 `replace("<<<", "<<")` 会把四连角括号留下三连，从而重新形成合法资料分隔符；同时标题/章节组成的 citation 显示标签原先可插入换行和伪造资料边界。

现在将连续三个及以上角括号统一缩为两个，清洗正文控制符，并将显示标签中的换行/控制符扁平为空格。输出引用元数据和原始资料保持原值以便溯源。黄金集覆盖三/四/更长连续字符及标题、章节攻击。这里只验证结构边界与完整指令存在，不代表模型必然抵御语义提示注入。

没有调整原 RAG recall/MRR/拒答门槛、mock embedding 阈值、ANN 查询/索引或性能参数；未改变 API、事件契约、音频模式、数据库 schema/迁移或控制台。因此不触发本层之外的 ANN 性能、浏览器和真实厂商验收。

## 验证命令与结果

使用既有 JDK 21.0.12.1、Maven 3.9.16 与本地缓存，命令在项目根目录执行：

```powershell
. .\script\dev-env.ps1
mvn -o -s target/m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-Dtest=OfflineGoldenSetTest,ContextGoldenSetTest,WorkflowGoldenSetTest,RagPromptGoldenSetTest' test
mvn -o -s target/m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests=false' test
mvn -o -s target/m4-3a-maven-settings.xml '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-DskipTests' package
```

| 验证 | 结果 | 证据（`target/m4-3d/`） |
| --- | --- | --- |
| 初次定向 | 72 条，0 失败/错误/跳过 | `layer2-20261014-targeted.log` |
| 补强后定向 | 72 条，0 失败/错误/跳过 | `layer2-20261014-targeted-final.log` |
| 最终全量 | 137 个套件、975 条，0 失败/错误、6 跳过 | `layer2-20261014-tests-final.log` |
| 离线打包 | BUILD SUCCESS | `layer2-20261014-package.log` |
| 独立交付包 | 94,366,070 bytes | `layer2-20261014-engine.jar` / `.jar.sha256` |
| 三组报告与脱敏快照 | 24/21/18 全 PASS、NOT_RUN=0；只保留统计、路径与哈希 | `layer2-20261014-*-report.json` / `layer2-20261014-delivery.json` |

独立 JAR SHA256：`a35bbd2f2751a3da304c40dc152487f7ace9eab3a332281d50460593949809a4`。包内 `ContextPromptAssembler.class` 与本轮编译产物哈希一致，测试设施与合成夹具未进入交付 JAR。三份报告逐行核对 fixture id/类别、总数、资源哈希及字段白名单。

6 条跳过为 5 条真实 Redis 集成（`aiflow.redis.integration=true`）及 1 条真实 embedding 校准（`aiflow.eval.embedding.mode=http`）；本轮没有重新声明外部设施容量或真实模型验收。全量包含既有记忆/画像/安全/额度/执行/音频 API 与生命周期回归。既有离线 RAG 指标为 recall@5=0.9231、MRR=0.9182、拒答准确率=0.9091，门槛与结果未降低。

未使用 `clean` 或删除旧产物；本轮无新增依赖、在线下载、真实厂商或付费模型调用。没有修改 `.env`、真实密钥、CI/CD 或系统配置，也没有 push/rebase。项目尚无首个提交，源码整体未跟踪，`git diff --check` 不能单独证明完整差异；交付快照另记录源码/资源/文档及产物 SHA256。

## 验收边界与下一步

流式已输出后禁止的是本次 provider 调用换档，节点 RetryPolicy 仍可重试整轮；部分流场景显式使用 `maxAttempts=1`。最终长度审核可以替换最终答复，但此前合规发出的流片段仍可能已经可见。空 RAG 只证明组装了完整拒答指令，未评分模型实际拒答能力。真实模型的回答、语义防注入、ASR 转写与 TTS 发音质量，以及真实额度/计费仍待 API/模型准备后另验。

下一步为使用与运维收口：先明确启动/配置自检和目录规则，再统一离线启动入口、补部署/故障排查说明，并以 mock 完成一次端到端演示验收。真实厂商激活单独核对官方协议、provider 装配、能力门禁与小样本质量/量程；账号权限深化继续后置。
