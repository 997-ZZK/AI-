# 云端知识库离线协议边界

本切片只完成厂商无关的 HTTP/JSON 适配边界与本地协议桩，不表示任何真实厂商已联调。默认 `cloud.enabled=false`，mock 保持离线；真实密钥、真实语料和计费调用不参与验收。

## 分层与兼容

- `RetrievalProvider` 是引擎端口；`KnowledgeBaseProtocol` 只负责请求和命中字段映射；`HttpKnowledgeBaseProvider` 负责传输、限长、有限重试和固定异常。协议实现放现有 `engine/integration`，不引入厂商 SDK 或新依赖。
- 默认兼容现有自定义 `POST /retrieve`，请求字段 `dataset_id/query/top_k`、响应数组 `/chunks`。它是网关契约，不能称作厂商原生协议。
- `cloud.http` 提供相对检索路径、凭证环境变量名、认证头/方案、请求/响应字节上限、并发上限和最大尝试次数；`cloud.http.mapping` 仅允许 JSON 字段名与 JSON Pointer，无脚本、模板执行或任意代码。
- `Cloud` 保留旧九参 Java 构造方法。遗留 `api-key` 字段只供旧配置绑定兼容；新的 HTTP 适配不读取它，凭证值只在调用时从 `api-key-env` 指定环境变量读取。配置打印不包含凭证值。
- 迁移旧部署时，将 `cloud.api-key: ${AIFLOW_CLOUD_KB_API_KEY:}` 改为 `cloud.http.api-key-env: AIFLOW_CLOUD_KB_API_KEY`（只保留变量名）。HTTP 模式发现非空旧 `api-key` 且没有 `api-key-env` 时固定 `CONFIGURATION` 失败，避免静默丢失鉴权。

## 命中与分数

- 必须有非空文档 ID、切片 ID、正文和有限数值 score；缺 score 不能默认为 0。原始 score 留给统一 `ScoreNormalizer`，映射层不自行归一化。
- 可映射标题、章节、页码、content hash、真实 chunk index。缺 hash 时对正文计算 SHA-256；缺标题回退文档 ID。
- 缺 chunk index 使用 `-1` 表示未知，不用相关性结果数组的序号冒充文档顺序。只有同 source、同 docId、已知且连续的非负索引能相邻合并；未知顺序命中独立保留。
- 页码必须为正整数，起止不能倒置；索引存在时必须为非负整数。任何结构错误整体失败，不静默过滤坏命中。

## HTTP 行为与错误

- 不跟随重定向；凭证不会发往服务器返回的新地址。配置地址须为 HTTP(S)，不得携带 userinfo、query 或 fragment；检索路径必须为无 query/fragment 的绝对路径。
- 默认 4 秒总 deadline（覆盖重试、响应头与响应 body），连接等待不超过该限制；1MiB 请求、4MiB 响应、4 个并发调用。满载快速返回固定 `OVERLOADED`；请求/响应超限分别为 `REQUEST_TOO_LARGE` / `RESPONSE_TOO_LARGE`。总 deadline 配置范围 1..120000ms，请求/响应限额各 1B..16MiB，并发 1..64。
- 默认最多 1 次尝试（即不自动重试）；配置 2..3 次还必须显式 `retry-safe=true`，确认该 POST 的重复请求不会造成不可接受的计费或副作用。只有超时、传输错误、429、502/503/504 可有限重试；401/403、其他 4xx、结构错误不重试。服务端出现 `Retry-After` 时停止自动重试，交还调用方决定后续时机，不提前重试。
- 凭证只允许长度不超过 8192 的 ASCII 可见字符；认证头禁止 `Host`、`Content-Length`、`Connection` 等 HTTP 保留头，避免绕过固定异常与脱敏边界。
- 异常只包含固定原因、HTTP 状态与可重试标志；不保留原始 HTTP 异常 cause，不回显 URL、头部、查询正文或服务端报文。取消/中断不继续重试。
- 本切片不新增表，不变更本地 ANN 查询、候选数、阈值或参数，不访问真实厂商。

## 离线验收

本地 JDK `HttpServer` 验证默认契约、自定义字段映射、分数保真、缺字段、未知顺序、限长、超时、401/429/5xx、有限重试、重定向拒绝及异常脱敏；检索后处理与 RAG 黄金集回归由统一验收运行。真实厂商开放后，仍需官方协议核对、真实单样本和真实分数量程校准。

非敏感配置示例（在本地协议桩验收后才将 `enabled` 改为 true）：

```yaml
aiflow:
  retrieval:
    cloud:
      enabled: false
      mode: http
      base-url: http://127.0.0.1:19090/v1
      dataset-id: synthetic-dataset
      http:
        retrieve-path: /retrieve
        api-key-env: AIFLOW_CLOUD_KB_API_KEY
        max-request-bytes: 1048576
        max-response-bytes: 4194304
        max-concurrent-calls: 4
        max-attempts: 1
        retry-safe: false
        mapping:
          chunks-pointer: /chunks
          document-id-pointer: /document_id
          chunk-index-pointer: /chunk_index
          score-pointer: /score
```
