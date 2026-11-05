# M03 模型工厂与最小闭环

- `get_model(role)` 是唯一入口，业务代码不关心底层是 DeepSeek 还是别的 OpenAI 兼容服务。
- 错误映射：超时 → TIMEOUT、429 → RATE_LIMITED、401/403 → NOT_CONFIGURED、其余 → UPSTREAM_ERROR；映射集中在 `app/core/errors.py`。
- SSE 用 `sse-starlette`：`ping=15` 自动发注释心跳；事件数据用 Pydantic `model_dump_json(by_alias=True)` 序列化，保证 camelCase 与 UTC。
- 建立流之前的错误（未配置 key、问题过长、会话不存在）走普通 HTTP 错误，不进入 SSE；只有流内错误才发 `error` 事件。
- 非流式 `/api/chat` 与流式共用同一次「检索 + 组装」。
