# 已知问题

记录跨模块遗留、暂不修复的问题，避免在后续模块里重复踩坑。

## 模块二：时间线

1. **真实停库时的 500/503 未完全实测**
   - 现状：模块四 3.5 已实测 `/api/chat` 与 `/api/chat/stream` 在停库时返回 503/50300；`/api/health` 与 `/api/timeline` 尚未在本轮真停库复测。
   - 待办：本机 `docker compose stop postgres` 后调用 `/api/health` 和时间线接口，确认状态码与响应体。

2. **`GET /api/timeline?page=abc` 返回 500**
   - 现状：`page` / `size` 由 Spring 直接绑定为 `int`，非法值抛 `MethodArgumentTypeMismatchException`，`GlobalExceptionHandler` 没有对应处理，落到兜底 500。
   - 待办：在 `GlobalExceptionHandler` 增加 `MethodArgumentTypeMismatchException` → 400；或用 `@Min` + 字符串接收后手动解析。不属于模块三范围。

## 模块三：AI 接入

暂无。真实 API 调用、SSE 实时性、取消/心跳需要本机验证，见 `docs/design/module-03-ai.md` 第 10.3 节。

## 模块四：最小端到端闭环

1. **无 key 预检只覆盖「apiKey 为空」**
   - 现状：`ChatClient.isConfigured()` 只检查空白；key 填错（401）或余额不足（402）仍会建立 SSE，通过 `event:error` 返回，而不是 HTTP 503。
   - 待办：不做启动期/请求期探活；真实错误由 `ChatClient` 分类，前端按 `errorType` 处理。

2. **非连接类数据库异常仍返回 500**
   - 现状：`GlobalExceptionHandler` 已覆盖 `MyBatisSystemException` / `PersistenceException` 的**连接类**根因（SQLState `08xx`/`57P0x`、`SQLTransientConnectionException`、`CannotGetJdbcConnectionException`、`ConnectException` 等）→ 50300；SQL 语法、约束冲突等非连接异常仍走兜底 500，这是有意保留的。
   - 待办：无；如果以后出现其他连接类异常变体，先补 `isConnectionFailure` 规则，不要整类映射成 503。

3. **单轮无状态**
   - 现状：`POST /api/chat` 不接受 history，「那第二天呢」会回落默认窗口，可能答非所问。
   - 待办：模块十（conversation/message + query rewriting），不是本模块缺陷。

4. **关键词只覆盖高置信情形**
   - 现状：只抽引号短语和 ASCII 词；中文自由文本不做分词，检索退化为时间窗。关键词 0 命中会自动回退，但 AND 多词仍可能过滤过窄。
   - 待办：模块八做统一的查询理解；当前用回退兜底。
