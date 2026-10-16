# API 接入指南

## 鉴权
当前版本按 userId 传参，尚未接入鉴权，生产部署前必须补齐。

## 主要端点
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/users | 创建用户 |
| POST | /api/v1/sessions | 创建会话，可绑定工作流名 |
| POST | /api/v1/sessions/{id}/messages | 发消息，返回 runId |
| GET | /api/v1/runs/{id}/events | SSE 事件流，支持 Last-Event-ID 续传 |
| POST | /api/v1/kbs/{kbId}/documents | 文本入库 |
| POST | /api/v1/kbs/{kbId}/documents/upload | 文件上传入库 |
| POST | /api/v1/kbs/{kbId}/retrieve | 检索调试 |
| POST | /api/v1/kbs/{kbId}/reindex | 重建索引 |

## 错误码
400 参数错误、404 资源不存在、429 会话队列已满、503 检索来源不可用或额度硬上限、
500 服务内部错误。错误响应统一含 code、message、traceId。

## 限流与背压
同一会话串行执行，队列满时快速失败返回 429，不做无限等待。
跨会话并发执行，互不阻塞。

## 示例
POST /api/v1/sessions/{{sessionId}}/messages
{"clientMsgId":"m1","content":"发票抬头要写什么？"}
响应返回 runId，再通过 SSE 端点订阅执行过程。