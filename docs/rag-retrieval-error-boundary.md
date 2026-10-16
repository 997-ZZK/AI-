# RAG 检索错误边界

## 目标

`RagRetrievalService` 会调用可插拔的 embedding 与 `RetrievalProvider` 实现。自定义实现抛出的异常正文、`toString()`、cause 或 suppressed 异常可能包含查询内容、知识库地址、凭证片段或厂商报文，不能进入检索异常、节点失败结果、日志或“所有来源失败”的聚合信息。

## 固定契约

- embedding 调用失败统一转换为 `RetrievalException` 的固定分类；向量维度不符仍保留“维度不符”分类，成功向量与现有维度校验不变。
- 每个来源的 `healthy()` 或 `retrieve()` 抛出任意运行时异常时，记录固定的来源失败分类并继续尝试其他来源；日志只保留来源标识和固定分类，不打印异常对象、message、cause 或 suppressed 链。
- 所有来源失败时，`RetrievalException` 只包含固定总失败说明与来源标识，不拼接 provider 原始 message；只有引擎或已收口的适配器通过安全构造生成的可操作诊断可以保留（例如本地索引需要重建时的模型标识和命令、云知识库的固定原因和 HTTP 状态）；部分来源成功时，仍返回成功来源和已收集结果。
- 来源过滤、健康检查、分数归一化、融合去重、后处理和提示词组装语义保持不变。
- 本轮只收紧 `RagRetrievalService` 的通用异常边界，不改变厂商协议、配置、数据库、索引、阈值或真实模型调用。

## 验证

使用带合成敏感标记的自定义 embedding/provider，覆盖 embedding 异常、来源 `healthy()` 异常、来源 `retrieve()` 异常及 cause/suppressed 链。断言 `RetrievalException`、节点失败结果和捕获日志均不含敏感标记，同时回归部分降级、全失败、维度不符和正常融合路径。
