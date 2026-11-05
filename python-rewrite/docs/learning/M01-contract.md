# M01 契约冻结

- 契约来源不是设计文档，而是 **前端 `frontend/src/api/types.ts` 与 `sse.ts`**：字段名、可空性、事件名逐项对照。
- 发现与计划书不一致的一处：SSE 端点是 `/api/chat/stream`，不是 `/api/chat/ask`。以代码为准。
- 所有 JSON 接口统一 `{code, message, data}`；业务码与 HTTP 状态码分离（如 50301 对应 HTTP 503）。
- SSE 顺序写死为 `start → step* → sources → delta* → done`，`sources` 必须在首个 delta 之前；心跳是注释行 `: ping`。
- 时间契约：对外一律 UTC ISO-8601（`+00:00`），前端自己转本地时区；文档来源的 `startTime/endTime` 恒为 null，避免把知识误当成经历。
- 产出：`docs/contract/openapi.yaml`（由应用 OpenAPI 导出）、`sse-events.md`、`parity-checklist.md`。
