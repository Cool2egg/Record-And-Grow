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

## 模块五：文档入库

1. **不做 OCR / 表格结构化 / docx**
   - 现状：只支持 PDF / Markdown / TXT；解析后正文少于 50 字直接判 422，文案提示「可能是扫描件或空文档」。
   - 待办：不做；扫描件需要 OCR 时另起模块。

2. **jtokkit 与 Qwen 分词不一致**
   - 现状：切块用 `cl100k_base`，中文大约多算 1.5–2 倍；偏差方向是「块更小」，不会撞 8192 输入上限，但 400 token 的块在供应商侧实际更小。
   - 待办：写进验证文档即可；要精确可换 DJL + Qwen tokenizer，`TokenCounter` 接口已隔离。

3. **没有 outbox 表**
   - 现状：「先落库、事务外发消息」+ 对账补发兜底；极端情况下（落库成功、发送失败、进程立刻挂掉）文档会停在 PENDING，最多等 10 分钟 + 5 分钟对账周期。
   - 待办：记作已知限制；面试时可讲「严格做法是 outbox」。

4. **不支持多实例消费**
   - 现状：对账任务每个实例各跑一遍，同一文档可能被并发解析；parse 的「先删后插」在并发下会撞唯一索引或产生半成品。幂等只保证「重复执行结果一致」，不保证「交叉执行安全」。
   - 待办：单实例部署；要扩容先加文档级租约（`processing_owner` / `lease_until`）。

5. **按 token 切块后成本上升**
   - 现状：块数比按字符切多约 1 倍，embedding 调用次数与费用相应上升。
   - 待办：需要时调大 `max-tokens`；偏差已量化在验证文档。

6. **local 模式没有重试**
   - 现状：`LocalIngestQueue` 直调同步入口，失败一次就标 FAILED（测试与演示要确定性）。生产走 RocketMQ 才有 3 次重投。
   - 待办：无；local 只用于测试与无 broker 调试。

7. **删除原文失败只记日志**
   - 现状：软删文档后删文件，IO 失败不影响接口成功，可能残留孤儿文件。
   - 待办：无；文件按 sha256 命名，重传会复用同一路径，不会无限增长。

8. **对账只看心跳**
   - 现状：`last_progress_at` 超过 10 分钟没更新才补发；如果某批 embedding 本身卡住超过 10 分钟（供应商极慢），仍可能被误判。
   - 待办：正常单批 30s 超时 × 2 次尝试，远小于 10 分钟；真出现再调 `stale-after`。
