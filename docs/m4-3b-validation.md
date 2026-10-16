# M4-3B 验收记录（2026-10-09）

本阶段已实现并验收：V10 用户画像控制状态、最近30天行为统计、停用/逻辑重置，以及手工白名单偏好应用。M4-3C/D 尚未进入实现。契约见 [profile-design.md](profile-design.md)。

## 实现与边界

- `user_profiles` 仅存开关、统计起点、时区、版本和时间列；不复制消息、审计或偏好正文。默认关闭，GET 不建行。写操作锁用户行并校验 `expectedVersion`，旧版本409，非法时区/版本400。
- 统计窗口为最近30天与启用起点中较晚者。运行按 `created_at`、工具按持久审计 `at`、chat/vision 节点尝试按自身 `started_at` 过滤；审批恢复不重复算运行。小时分布使用画像时区。
- mock、真实 provider、来源未知的已记录 token 分列；缺失、负数、小数或溢出用量计 unknown。工具 SUCCESS/REPLAYED/FAILED/DENIED 分列，并返回审计开关、保留期与本实例丢写诊断。
- DELETE 是逻辑重置并停用，保留原运行、审计和长期记忆。再启用新起点排除此前行为；持续开启只改时区保留起点。
- 偏好读取 `user_memories` 中 MANUAL/PREFERENCE 的两个规范槽位：`response-style=concise|balanced|detailed`、`model-policy=default|cheap-first`。回复风格映射固定模板，不注入任意偏好正文。
- chat/vision 共用一次详细装配结果；画像、长期记忆与摘要使用各自标签，会话预览新增 `profileIncluded`，正确计算历史条数且不回写 hits。`profilePreference=none` 禁用画像应用，显式 model 不因画像 cheap-first 改档；预算 SOFT 仍可优先便宜档，硬额度继续拦截。
- 停用画像不关闭原 M4-2 长期记忆召回。当前没有 Subject→userId 归属映射，接口仍用于本地开发；本切片不宣称完整多用户隔离。工作流分布不是语义话题分类，已记录 token 不是完整消费账单。

## 自动化验证

使用既有 JDK21/Maven3.9.16 与离线依赖，Maven 串行执行；默认沙盒在现有 `.m2` jar 读取处报 AccessDenied，使用本地执行提权后通过，未清缓存或安装全局依赖。

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -o `
  -s target/m4-3a-maven-settings.xml `
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository' `
  '-Daiflow.redis.integration=true' test

& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -o `
  -s target/m4-3a-maven-settings.xml `
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository' -DskipTests package
```

最终全量 **606 条、0 失败、0 错误、1 跳过**，108 个测试类；真实 Redis 集成已开启。唯一跳过为 `RagRealEmbeddingCalibrationTest` 的真实 embedding 校准。日志 `target/m4-3b-final-full-test.log`，打包 `BUILD SUCCESS` 见 `target/m4-3b-package.log`。汇总 `target/m4-3b/test-summary.json`。

本切片新增37条：ProfileIntegrationTest 9、ProfileServiceTest 5、ProfileStatisticsPersistenceTest 2、ProfileStatisticsRepositoryTest 2、ProfilePreferenceResolverTest 7、ProfileStatisticsTest 4、LlmNodeProfilePreferenceTest 6，以及 SessionMemoryServiceTest 新增2条标签回归。覆盖首次启用并发单赢家、30天/启用起点、时区、版本冲突、保留源记录、unknown token、白名单与停用、chat/vision/预算/显式模型接线及预览一致。

实施验收中发现 JPA 偏好适配读到了关闭态但返回固定开启的 Snapshot；已改为 `Snapshot(enabled, entries)`，停用回归通过。H2 将纳秒时间存为微秒时存在舍入，时区测试采用≤1000ns容差，仍验证统计起点不重置。控制台用户切换会清空旧视图，旧用户响应与低版本响应不得覆盖当前页面。

收尾复核发现预览只按标签识别块：停用画像的空会话输入画像标签后，真实浏览器显示 `profileIncluded=true/historyCount=-1`。已改为 `role=system` 与标签共同识别，两条新增测试分别遍历三种标签，覆盖当前输入与历史user/assistant回显。修复后完整重跑606条与打包，不用此前604条的结果代替最终基线。

## 真实 PostgreSQL / Redis 验收

独立应用在8082，唯一 JVM 标记 `m4-3b-20261009`、首轮PID45128；修复后以相同参数重启为PID37060，记录 `target/m4-3b/engine-restart.json`，当前进程开关记录 `engine-process.json`。使用真实 PostgreSQL16.15/pgvector、Redis，Flyway 从V9成功执行V10，health为200/UP。

对话和 embedding 均为 mock；local/cloud/ark/qwen/deepseek 全部禁用，probe=false，云知识库关闭，长期抽取关闭。为记忆回归开启 mock 摘要，window-size=4、summary-chunk-size=2。所有写入仅用合成用户、会话和工作流。

```powershell
& .\script\verify-profile-flow.ps1 -BaseUrl http://localhost:8082 -OutFile target/m4-3b/profile-flow-final.json
& .\script\verify-memory-flow.ps1 -BaseUrl http://localhost:8082 -OutFile target/m4-3b/memory-flow-final.json
& .\script\verify-approval-flow.ps1 -BaseUrl http://localhost:8082 -OutFile target/m4-3b/approval-flow-verify.json
& .\script\verify-long-memory-flow.ps1 -BaseUrl http://localhost:8082 -OutFile target/m4-3b/long-memory-flow-verify.json
```

| 验收 | 结果 | `target/m4-3b/` 报告 |
| --- | --- | --- |
| 默认关闭/GET不建行、启用与时区、真实运行、PG token精确对照、偏好/none、409/400、逻辑重置保留偏好、再启用排除旧run、profileIncluded、工具覆盖边界 | 23/23 | profile-flow-final.json |
| 窗口、mock摘要折叠、摘要token、装配、重建保留成本与404 | 20/20 | memory-flow-final.json |
| 挂起、同会话后续消息不被锁阻塞、批准恢复、取消清待办 | 4/4 | approval-flow-verify.json |
| 手工记忆、纠正单槽位、召回、跨会话装配、运行命中计数、删除本脚本合成条目、敏感写入与404/400护栏 | 21/21 | long-memory-flow-verify.json |

最终画像合成 user `54ef01e8-28a9-44a9-8452-9d987e30d172`、run `74a2c2e2-08d2-4576-b002-9d4a2cc36e03`。脚本仅以SELECT独立对照PG统计；重置后保留两条手工偏好，再启用统计为0。首轮画像/短期记忆报告仍保留在 `profile-flow-verify.json`、`memory-flow-verify.json`；最终jar复跑上述两组，审批与长期记忆为标签修复前的存量真实栈回归。长期记忆回归未带 `-ExpectExtraction`，只删除该脚本新建的合成条目，未删除既有用户记录、文件、卷或Git历史。

## 控制台浏览器验收

真实浏览器打开 `http://localhost:8082/console.html`，仅使用 `profile-ui-20261009-1/2` 合成用户，逐步操作以下流程，warn/error日志为空：

1. 查询默认关闭且版本0；启用为版本1、时区Asia/Shanghai。
2. 保存详细回复与cheap-first，两条均显示MANUAL/PREFERENCE、applied。
3. 工作流none显示workflow-disabled；显式mock模型使model-policy显示explicit-model、回复风格仍生效。
4. 持续启用时改UTC，版本2、起点不变；停用版本3，预览显示profile-disabled。
5. 逻辑重置版本4并更新起点，长期记忆列表仍有两条偏好，hits均为0。
6. 切换第二用户，显示默认版本0并清空旧偏好/记忆视图；返回首用户仍为版本4。
7. 最终jar重新加载页面；停用画像的新空会话输入画像标签，返回 `profileIncluded=false/historyCount=0`，共1条user消息，warn/error日志为空。

观察报告 `target/m4-3b/profile-ui-verify.json`，截图 `profile-ui-reset.jpg`、`profile-ui-preview.jpg`；标签修复前后为 `profile-label-before.json`、`profile-label-after.json`，最终截图 `profile-label-after.jpg`。

## 未执行项与收尾

真实付费长期抽取、真实 embedding 校准、真实模型回复质量未重跑；本轮没有修改检索阈值或ANN参数。工具审计关闭/未知用量等边界由自动化测试覆盖，真实画像脚本没有伪造真实工具调用。大量SSE与200并发容量仍留M4-3C，不使用B正确性报告作容量承诺。

已核对JVM标记并仅终止本轮PID37060；PostgreSQL/Redis容器、合成数据、日志和报告保留，进程收尾记录 `target/m4-3b/engine-stop.json`。未修改 `.env`、真实凭证、CI或系统配置，未push/发布。Git仍是无commit的main且源码未跟踪，验收证据为文件、测试与报告。
