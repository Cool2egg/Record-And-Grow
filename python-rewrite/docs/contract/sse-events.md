# SSE 事件契约

> 状态：已冻结（对应 Java v0.9.9 行为）。实现以此为准，前端 v0.9.9 零改动。

## 端点

```
POST /api/chat/stream
Accept: text/event-stream
Content-Type: application/json
```

请求体：

```json
{ "question": "我最近在学什么", "conversationId": 12, "maxTokens": 1024, "temperature": 0.3 }
```

- `conversationId` 可选；为空时服务端新建会话，并在 `start` 事件里回传新 id。
- 建立流之前的错误（参数错误、未配置模型、会话不存在）以普通 HTTP 错误 + `{code,message,data}` 返回，不是 SSE 事件。

响应头：`Content-Type: text/event-stream; charset=utf-8`、`Cache-Control: no-cache`、`X-Accel-Buffering: no`。

## 事件顺序（硬约束）

```
start → step* → sources → delta* → done
                 ↑                    ↑
          固定在首个 delta 之前     或 error 终结
```

- `step` 只出现在 Agent 检索阶段，可以没有；全部 `step` 都在 `sources` 之前。
- `sources` 恰好一次。
- 任一时刻只可能以 `done` 或 `error` 结束，不会二者都有。
- 心跳：每 15 秒一行注释 `: ping`，任何时刻都可能插入，前端必须忽略以 `:` 开头的行。

## 事件载荷

### start

```json
{ "requestId": "uuid", "conversationId": 12 }
```

### step（检索过程，可多条）

```json
{ "seq": 1, "tool": "search_timeline", "query": "上个月跑步", "hits": 3, "ok": true, "ms": 42, "error": null }
```

`seq` 从 1 递增；`query` 最长 120 字符；失败时 `ok=false`、`hits=0`、`error` 为简短原因。

### sources（恰好一次，位于首个 delta 之前）

```json
{
  "items": [
    {
      "ref": "T1", "type": "timeline", "id": 9,
      "startTime": "2026-11-01T09:00:00+00:00", "endTime": null,
      "excerpt": "跑步 30 分钟", "chunkId": null, "title": null,
      "heading": null, "score": null, "memoryType": null, "documentId": null
    },
    {
      "ref": "D1", "type": "document", "id": 3,
      "startTime": null, "endTime": null, "excerpt": "……",
      "chunkId": 44, "title": "学习计划", "heading": "计划 / 每周",
      "score": 0.873421, "memoryType": null, "documentId": null
    }
  ],
  "retrieval": {
    "windowStart": "2026-10-05T00:00:00+00:00", "windowEnd": "2026-11-03T07:30:00+00:00",
    "timeBasis": "DEFAULT", "clamped": false,
    "matched": 12, "retrieved": 12, "included": 1,
    "limitTruncated": false, "budgetTruncated": false,
    "keywords": [], "keywordFallback": false,
    "documentSearched": true, "documentsRetrieved": 2, "documentIncluded": 1,
    "totalIncluded": 2, "documentsDegraded": false, "degradeReason": null,
    "memoryIncluded": 0, "graphIncluded": 0
  }
}
```

不变式：

- `items.length == totalIncluded == included + documentIncluded + memoryIncluded + graphIncluded`
- `ref` 与正文引用一一对应：时间线 `T1..Tn`、文档 `D1..Dn`、记忆 `M1..Mn`、图谱 `G1..Gn`。
- 文档来源的 `startTime/endTime` 恒为 `null`（文档不是用户经历）。
- `documentSearched=false` 表示没有执行文档检索（关闭、无 READY 文档、降级或路由跳过）；0 条命中也是 `true`。

### delta（可多条）

```json
{ "content": "你这段时间" }
```

只追加，不覆盖。

### done（终结）

```json
{ "finishReason": "stop", "usage": { "promptTokens": 1200, "completionTokens": 300, "totalTokens": 1500 } }
```

### error（终结）

```json
{ "code": 50301, "errorType": "TIMEOUT", "message": "AI 服务响应超时", "partial": true, "requestId": "uuid" }
```

`partial=true` 表示在错误之前已经发过部分 delta，前端应保留已显示内容。

## 业务码

| code | HTTP | 含义 |
| --- | --- | --- |
| 0 | 200 | 成功 |
| 40000 | 400 | 请求参数错误 |
| 40400 | 404 | 资源不存在 |
| 50000 | 500 | 系统内部错误 |
| 50300 | 503 | 数据库不可用 |
| 50301 | 503 | AI 服务暂时不可用 |
| 50201 | 502 | AI 服务返回异常 |
| 41500 | 415 | 不支持的文件类型 |
| 42200 | 422 | 文档解析失败 |
| 40900 | 409 | 文档正在处理中 |
| 41300 | 413 | 文件过大 |
| 50302 | 503 | 消息队列不可用 |
