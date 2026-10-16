# M4-3A 验收记录（2026-10-09）

本阶段实现和核心验收完成：V9 持久调度、跨实例顺序与取消、PG 写入围栏、Redis 锁续租，以及最多 2000 条/最后事件后 24 小时的 Redis Stream 回放。B/C/D 未进入实现。

## 实现与边界

- USER、run、PROCESS 在同一 PG 事务提交；最终审批决定与 RESUME 同事务。队列溢出 429 不留下半成品、不占幂等键。
- 同会话按持久任务 seq 执行；PG task 以数据库时刻验证 owner/status/有效租约，写入与提交前均校验。控制路径遵守 session（入队时）→task→run→approval 锁序。
- 挂起结束当前执行段并释放会话锁；恢复不重放已完成节点。RUNNING 崩溃按结果不确定收 FAILED，不自动重试工具；QUEUED 可以由存活实例推进。
- Redis 会话锁按 TTL/3 续租。失租停止新节点、DELTA、重试、fallback 与旧 owner 落库。默认 Redis 命令/连接等待 2s；小 TTL 验证用 500ms 等待和 200ms XREAD block。
- 事件使用 XREAD 广播、数字 seq/SSE id、每连接有界队列。非法游标 400，已裁剪/过期/确认缺口 409；已建流以无运行 seq 的 STREAM_ERROR 关闭，PG 快照为事实来源。
- Compose Redis 使用 AOF everysec 与命名卷。PG 与 Redis 没有 outbox 原子交付，不能保证提交后必有事件；在分配 seq 前发布失败时，连续序号也不能证明语义事件完整。外部工具副作用没有精确一次保证，AOF 主机故障存在最后一秒丢写边界。

## 自动化验证

最终全量命令在已有 JDK 21/Maven 3.9.16 下执行，无全局安装；为了匹配既有缓存，使用 target 下的临时 Maven mirror 设置和显式 cache 路径：

```powershell
$env:JAVA_HOME='D:\tools\jdk21\jdk-21.0.12.1+1'
& 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' -o -s target/m4-3a-maven-settings.xml `
  '-Dmaven.repo.local=C:\Users\99730\.m2\repository' '-Daiflow.redis.integration=true' test
```

最终结果：**569 条、0 失败、0 错误、1 跳过**，日志 `target/m4-3a-final-full-test.log`。跳过的是需要真实 embedding 的 `RagRealEmbeddingCalibrationTest`。打包成功日志 `target/m4-3a-final-package.log`。

新增契约验证含 PG store 15 条、持久业务接线 13 条、worker 7 条、失租停止/旧 future/中断/持久写入围栏、四个发布入口故障隔离，以及真实 Redis 5 条。慢连接队列满断开、不阻塞发布者、连接错误清理与异步缺口通知由 RunStreamServiceTest 覆盖。

实施中修复了测试回复变量 fixture、Mockito 嵌套 stubbing、Redis 锁开启时 Spring 构造器注入、验证脚本锁 key 与检查名插值；最终全量已统一复跑，不能把首轮失败报告当最终结果。

## 真实 PostgreSQL / Redis 验收

所有模型与 embedding 使用 mock，真实 provider 与探活关闭。A 8082 执行、B 8083 仅接收控制请求以证明不同执行进程；容量实例 8084 worker 关闭、容量 2。后续 B 作为唯一存活 worker 验崩溃恢复。V9 在真实 PostgreSQL 16.15 上由 Flyway 执行并确认成功。

| 验收 | 结果 | target/m4-3 报告 |
| --- | --- | --- |
| 同会话 10 条按 seq、无重叠；跨会话并行；幂等；B 审批/取消、不重放工具 | 16/16 | distributed-flow-verify.json |
| 并行审批/取消与已领取恢复/取消，两类各 5 次；10s 有界收口、无 task/node/checkpoint/回复矛盾 | 80/80 | dispatch-races-verify.json |
| A 发布/B 接收、跨实例续传、连续序号、非法/负数/未来游标 | 11/11 | redis-events-verify.json |
| 3s 锁与任务租约，执行 13013ms；替换所有者后旧 owner 停止；429 无残留、未消费幂等键 | 12/12 | dispatch-failures-verify.json |
| 只终止带本轮 JVM 标记的 A，先确认工具执行一次与下一段已入队 | 6/6 | dispatch-crash.json |
| B 收崩溃运行为 FAILED、原节点和工具各一次；QUEUED 完成 | 7/7 | dispatch-recovery.json |
| 应用重启后回放同一已完成 run | 12/12 | redis-events-replay.json |
| Redis 普通重启和容器重建，命名卷保留 seq/stream；重建后完整 SSE 回放 | 12/12 | redis-restart.json、redis-recreate.json、redis-events-recreated-replay.json |
| 新增调度、事件、SSE Prometheus 指标可抓取 | 10/10 | prometheus-metrics.json |

复现入口为 `script/verify-distributed-flow.ps1`、`verify-dispatch-races.ps1`、`verify-dispatch-failures.ps1`、`verify-redis-events.ps1`。四个脚本均在 PowerShell 7 与 5.1 解析通过，单 UTF-8 BOM。崩溃脚本必须提供匹配的本轮 JVM 标记与 PID，分 crash/recovery 两阶段，不删除文件、卷或数据库数据。

## 存量回归与未执行项

真实引擎/PG/Redis 回归：审批 4/4（approval-flow.json）、鉴权 10/10（auth-flow.json）、短期记忆 20/20（memory-flow.json，摘要 model=mock）、长期记忆手工写入/召回/跨会话装配 21/21（long-memory-flow.json）。鉴权仅使用本次验证进程内的一次性环境变量令牌，报告不记值或前缀。

当前终端没有 AIFLOW_QWEN_API_KEY，真实付费长期记忆抽取未执行，不把 21 项 mock/手工验证写成真实抽取通过。真实 embedding 校准未重跑；本阶段没有调整 ANN 或 embedding 阈值。真实网络慢消费者的容量与大量 SSE 连接留 M4-3C 压测，不在本阶段给出容量承诺。

任务表和 seq counter 尚无删除生命周期；合成验收数据与报告保留。本轮没有修改 .env、真实凭证、CI、系统配置或执行 Git push；源码仍未跟踪，验收依据为文件、脚本与报告，不依赖已有 Git diff。
