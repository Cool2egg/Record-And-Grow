# 已知问题

记录跨模块遗留、暂不修复的问题，避免在后续模块里重复踩坑。

## 模块二：时间线

1. **真实停库时的 500/503 未实测**
   - 现状：`/api/health` 与时间线接口在数据库不可用时应返回 503（`ErrorCode.DB_UNAVAILABLE`），但只在单测里用异常模拟过，没有真正停掉 PostgreSQL 验证。
   - 待办：本机 `docker compose stop postgres` 后调用 `/api/health` 和时间线接口，确认状态码与响应体。

2. **`GET /api/timeline?page=abc` 返回 500**
   - 现状：`page` / `size` 由 Spring 直接绑定为 `int`，非法值抛 `MethodArgumentTypeMismatchException`，`GlobalExceptionHandler` 没有对应处理，落到兜底 500。
   - 待办：在 `GlobalExceptionHandler` 增加 `MethodArgumentTypeMismatchException` → 400；或用 `@Min` + 字符串接收后手动解析。不属于模块三范围。

## 模块三：AI 接入

暂无。真实 API 调用、SSE 实时性、取消/心跳需要本机验证，见 `docs/design/module-03-ai.md` 第 10.3 节。
