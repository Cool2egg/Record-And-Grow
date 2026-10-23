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

## 模块六：文档检索

1. **文档检索没有关键词兜底**
   - 现状：只走向量。embedding 不可用（未配置 key、超时、429、5xx）时只能降级为「只查时间线」，回答里会明确说明文档没查；没有 `ILIKE` / 全文检索的兜底。
   - 待办：混合召回（关键词 + 向量 + RRF + rerank）留给模块九。

2. **向量召回有上限**
   - 现状：`topK=8`、阈值 `0.25`、`scan-limit=24` 都是初始经验值；没有 rerank、没有查询改写。Qwen 对中文无关文本的相似度常在 0.3–0.5，阈值偏低时可能放进弱相关内容，偏高时会全灭。
   - 待办：按验证文档 4.10 的方法用真实数据校准；调 `recordandgrow.chat.documents.*`，不改代码。

3. **换 embedding 模型后旧向量不参与检索**
   - 现状：SQL 按 `c.embedding_model = 当前模型` 过滤；换模型（或改 `ai.embedding.model`）后，旧文档的块会被静默排除，表现为「文档查了但没结果」。
   - 待办：换模型后对存量文档执行 reindex 才能恢复；需要时再写迁移脚本。

4. **非 READY 文档与「查了没中」都不可见**
   - 现状：`PENDING / PARSING / EMBEDDING / FAILED` 的文档被静默忽略（设计如此：块可能不全）；向量 0 命中或全部低于阈值时，Prompt 与模块四逐字一致，用户只能从 `sources.retrieval.documentSearched / documentsRetrieved` 看出「查过但没中」。
   - 待办：前端（模块十三）用这两个字段做提示；后端不增加文案。

5. **`chunk_truncated=true` 的文档只覆盖前 2000 块**
   - 现状：模块五的 `chunk.max-chunks=2000` 截断后，`READY` 文档只包含前 2000 块，检索结果可能不完整；本模块照常让这类文档参与检索。
   - 待办：无；超大文档需要更细的切分策略时另起模块。

6. **查询 embedding 串行增加首包延迟**
   - 现状：两路串行（时间线 → 文档），每次提问多一次 embedding 调用。正常 +0.3–0.8s；最坏等 8s（`query-timeout`）后降级再开流。
   - 待办：并行检索与异常合并留给模块九的融合层。

## 模块七：知识图谱

1. **Neo4j 只有协议测试；LightRAG 已实机验证**
   - 现状：`recordandgrow.graph.store=neo4j|lightrag` 可切换，外部存储失败自动回落 Postgres；
     WireMock 覆盖了 Cypher 请求形状、Basic Auth、LightRAG references 与 500 异常。
     LightRAG 1.5.7 已实机跑通写入（含 `file_source`）/异步处理/`/query`，并用第二实例
     `GRAPH_STORE=lightrag` 验证了 reindex + graph/search；Neo4j 真实服务只做了 HTTP/Cypher 冒烟。
   - 待办：Neo4j 用真实数据跑一次完整 reindex + search。

2. **实体抽取依赖 LLM，且只在 READY 后触发**
   - 现状：没有 key 时图始终为空；功能上线前已 READY 的文档需要手工 `POST /api/graph/reindex`。
   - 待办：如需自动补齐，可在启动时跑一次后台 reindex（当前不做，避免启动变慢）。

3. **中文实体匹配用 2 字滑窗，不是切词**
   - 现状：`GraphRetriever` 对连续汉字做 2 字滑窗，没有接 HanLP / jieba / SmartCN。
     取舍：图是辅助通道，实体本身是 LLM 抽出的短语，2 字滑窗对未登录词有召回，
     引入词典或 IK 服务的依赖/启动成本高于收益。
   - 副作用：停用词噪声、8 个词上限；LightRAG 模式已改为直接传原问题，
     不受滑窗影响（`GraphStore.search(question, terms, limit)`）。
   - 待办：图成为主链路时再接 HanLP/SmartCN，或复用 ES IK，并用评测集校准。

## 模块十：记忆系统

1. **摘要与提炼是单实例后台任务**
   - 现状：共用 2 个 daemon 线程；没有分布式锁。多实例部署时同一会话可能被并发摘要。
   - 待办：扩容前给摘要/提炼加数据库租约。

2. **提炼质量未系统评估**
   - 现状：2026-10-23 真实 key 下已验证能产出候选并支持确认/拒绝；但「什么该记、什么不该记」只做了人工抽看。
   - 待办：用 `eval/` 的样例跑一轮人工标注，统计候选准确率。

3. **记忆检索是子串匹配，不是语义检索**
   - 现状：`normalized LIKE`。说法不同（「远程办公」vs「在家工作」）召回不到。
   - 待办：记忆量上来后再考虑 embedding；当前优先低延迟与可解释。

## 模块十一：AgentScope 编排

1. **两段式比单 Agent 多一次模型调用**
   - 现状：先检索后回答，首包延迟比旧链路高；换来的是 sources 顺序稳定与引用编号稳定。
   - 待办：若延迟不可接受，可把「检索 Agent 无工具调用」的直接问答短路成单次调用。

2. **检索 Agent 不调工具时不会自动兜底**
   - 现状：只有检索 Agent 抛异常才回退固定双路；模型「偷懒」直接答时证据为空。
   - 待办：可在 `toolCalls==0` 且问题长度/意图满足条件时补一次固定检索。

3. **真实 key 只做了基础链路验证**
   - 现状：2026-10-23 已用真实 key 跑通 `/api/chat`、`/api/chat/stream`、工具调用、记忆提炼、图谱抽取；
     但没有做稳定性、成本与回答质量评估。
   - 待办：用 `eval/` 集统计引用准确率、工具调用次数、首包延迟。

4. **SSE 的 sources 在检索完成后才发**
   - 现状：客户端先收到 `start`，然后要等检索 Agent 跑完才收到 `sources` 与第一个 `delta`。
   - 待办：可在 `start` 后先发一个 `sources` 占位（`items=[]`），检索完成后再发最终版；但那会破坏「sources 只发一次」的契约，暂不做。

5. **回答可能编造引用编号**
   - 现状：真实 key 下出现过回答引用 `[G4][G5]`、但本轮 sources 只有 `G1/G2`。
   - 已做：Prompt 明确「历史编号不能复用、只能用本轮材料编号」；`AgentChatService` 记录
     `agent answer has unknown citations` WARN，不自动改写答案。
   - 待办：接评测后做引用校验/重写，或把非法引用替换成「来源不足」提示。

## 模块十二：MCP / Skills

1. **MCP Server 无鉴权**
   - 现状：`/mcp` 是 stateless、无 token、无租户隔离。
   - 待办：只监听本机/内网；对外必须加 token、限流与审计。

2. **技能匹配是字面包含**
   - 现状：`triggers` 子串命中，中文触发词可能过宽（如「总结」）。
   - 待办：需要更精确时改成「触发词 + 问题意图」双条件，或引入小模型分类。

3. **技能只注入回答 Prompt，不参与工具选择**
   - 现状：技能不能新增工具或改变检索策略，只能约束输出结构与口吻。
   - 待办：需要流程型技能时，再接 AgentScope 的 SkillBox。

## 测试与运行环境

1. **Testcontainers 测试默认排除**
   - 现状：`mvn test` 只跑单测；真库测试需要 `mvn -Pintegration test` 且本机有 Docker。
   - 待办：CI 上固定跑 integration profile。

2. **没有真实 key 时 `/api/chat` 返回 503**
   - 现状：`ChatClient.isConfigured()` 只看 key 是否为空；这是预期行为。
   - 待办：无。
