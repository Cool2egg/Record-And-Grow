# 模块七至十二验证记录

> 环境：Windows 10，JDK 21.0.12（Temurin），Maven 3.9.16，Spring Boot 4.0.8，AgentScope Java 2.0.4。
> 2026-10-23 复测：Docker 29.8.2 已启动，Postgres 容器 + Flyway V1–V4 已执行；
> DeepSeek / DashScope key 通过 IDEA Run Configuration 注入，真实 key 的对话与 SSE 已跑通。

## 1. 编译与单测

```bash
mvn -q -DskipTests compile
# 结果：BUILD SUCCESS

mvn test
# 结果：355 tests / 0 failures / 0 errors（57 个测试类，Testcontainers 测试默认排除）

mvn -Pintegration test
# 结果：389 tests / 0 failures / 0 errors（355 单测 + 34 真库/集成测试）
```

默认排除规则在 `pom.xml`：`<test.excludes>**/*IntegrationTest.java</test.excludes>`。
需要真库时：

```bash
mvn -Pintegration test
```

## 2. 新增模块的针对性测试

```bash
mvn -q -Dtest=AgentScopeSmokeTest test
# 2/0/0：ReAct 循环 + 反射注册工具

mvn -q -Dtest=OpenAiModelWireMockTest test
# 1/0/0：OpenAI 协议 + 流式 tool_calls → 工具执行 → 最终回答

mvn -q -Dtest=AgentChatServiceTest test
# 1/0/0：真实 ReActAgent + 工具，mock 模型与检索；验证 T1 编号、sources、落库

mvn -q -Dtest=AgentToolContextTest test
# 4/0/0：编号稳定、分节、预算截断、去重

mvn -q -Dtest=MemoryServiceTest,MemoryExtractorTest test
# 12/0/0：三态流转、去重、上限、JSON 容错

mvn -q -Dtest=GraphExtractorTest,GraphRetrieverTest test
# 6/0/0：三元组解析、禁用态、关键词与命中映射

mvn -q -Dtest=Neo4jGraphStoreTest,LightRagGraphStoreTest,FallbackGraphStoreTest test
# 11/0/0：Neo4j Cypher/Basic Auth/行解析、LightRAG references/insert/500、回落与双失败

mvn -q -Dtest=GraphStoreSelectionTest test
# 1/0/0：store=neo4j 时启动期只装配客户端，不连接外部存储

mvn -q -Dtest=SkillRegistryTest test
# 3/0/0：front matter / 标题简写 / 触发词匹配

mvn -q -Dtest=McpServerTest test
# 1/0/0：真实 Tomcat，initialize + tools/list 返回四个工具
```

## 3. 真库测试（已执行）

```bash
docker compose up -d postgres
docker compose run --rm flyway
# Successfully applied 2 migrations (V3 memory, V4 graph), now at version v4

mvn -Pintegration -Dtest=MemoryPersistenceIntegrationTest test
# 4/0/0：V3/V4 建表、会话历史、PENDING → INFERRED、图谱 1 跳

mvn -Pintegration test
# 389/0/0：全部单测 + 34 个真库/集成测试
```

覆盖：

- V3/V4 Flyway 建表；
- 会话消息写入与历史加载；
- PENDING → INFERRED 确认流转、确认后 `proposed_type` 置空；
- 图谱写入、1 跳查询、出处文档；
- 文档入库 / 检索 / 时间线等既有集成测试。

## 4. 手工验证

### 4.1 源码启动

已执行（Postgres 已起、无 key 时）：

```bash
curl http://127.0.0.1:8080/api/health
# {"database":"UP","status":"UP","vectorExtension":true}

curl http://127.0.0.1:8080/api/skills
# 返回 3 个技能：周报生成 / 学习计划 / 面试复盘
```

### 4.2 打包产物

```bash
mvn -q -DskipTests package
# target/record-and-grow-0.1.0.jar（约 213 MB，AgentScope/MCP 依赖较重）

# 注意：本机 PATH 上的 java 是 17，必须显式用 JDK 21 运行
"C:\Users\...\temurin-21.0.12.1\bin\java.exe" -jar target/record-and-grow-0.1.0.jar
```

实测（无 key、无 DB）：

```bash
curl -X POST http://127.0.0.1:8080/api/chat \
  -H "Content-Type: application/json" --data-binary @chat.json
# HTTP 503，body {"code":50301,...,"message":"AI 服务暂时不可用"}（无 key 预检，符合预期）

curl -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" --data-binary @mcp_init.json
# HTTP 200，result.serverInfo.name=recordandgrow，capabilities.tools=true
```

> Windows PowerShell 的 `curl.exe -d '{"..."}'` 会丢内层引号，导致
> `JSON parse error` / MCP `Invalid message format`。手工验证请用 `--data-binary @file`，
> 不要把这两类报错误判成应用 bug。
>
> 另外 PowerShell 5 的 `Set-Content -Encoding utf8` 会写 BOM，MCP 会把它当非法 JSON；
> 用 `[IO.File]::WriteAllText(path, json, New-Object System.Text.UTF8Encoding($false))`。

### 4.3 真实 key 端到端（IDEA 启动）

DeepSeek / DashScope key 配在 IDEA Run Configuration，应用从 IDEA 启动：

1. **非流式对话**：`POST /api/chat` → HTTP 200，4.3s，591 tokens，返回 `conversationId=1`。
2. **SSE 对话**：`POST /api/chat/stream`（问「根据我的读书笔记，给我一个下一步学习建议」）
   → `start`（conversationId=2）→ `sources` → 200+ 个 `delta` → `done`。
3. **发现并修复两个真实问题**：
   - 时间线一次命中 68 条，占满 60 个证据位，导致 `documentsRetrieved=8` 但
     `documentIncluded=0`。修复：`AgentToolContext` 按来源配额（时间线 45% / 文档 35% /
     记忆 10% / 图谱 10%）+ 证据区按权重分预算；新增
     `documentsShouldStillEnterWhenTimelineIsHuge` 测试。
   - 确认记忆后 `proposed_type` 没被清空（MyBatis-Plus 默认忽略 null 字段）。修复：
     改用带状态条件的 `UPDATE ... SET proposed_type = NULL`，并补真库断言。
4. **记忆闭环**：回答后产生 2 条 PENDING；确认后分别落 FACT / INFERRED；
   `GET /api/memories?type=FACT` 与 `/api/memories?type=INFERRED` 均可查；
   MCP `tools/call search_memory` 返回 `count=1`、`ref=M1`。
5. **图谱**：`POST /api/graph/reindex?limit=3` → `indexed=3`；
   `/api/graph/stats` → `nodes=35, edges=28`；`/api/graph/search?q=学习` 命中
   `英语学习计划 -包含-> 目标`。
6. **MCP**：`initialize` 200；`tools/list` 4 个工具；`tools/call search_memory` 200。

> 4.3 的修复需要**重启 IDEA 应用**才会生效；重启前的 class 是旧的。

### 4.4 重启后的最终验收（2026-10-23）

同一问题「根据我的读书笔记，给我一个下一步学习建议」：

- `sources`：时间线 27、文档 11、图谱 5，`totalIncluded=43`，
  `documentsRetrieved=11 == documentIncluded=11`，不再自相矛盾；
- 回答 **586 字**自然结束，`completionTokens=377`（而不是撞到 1024），
  引用 `[T4] [D9] [G3] [G4] [G5] [T11] [D1] [D2]` 全部能在本轮 sources 中找到；
- 多轮续问「那我应该先读哪一本？」使用同一个 `conversationId=6`，
  回答 413 字，引用全部合法；
- 两轮对话后 `GET /api/memories/pending` 为空：提炼 Prompt 带已有记忆后，
  没有再刷出重复候选；
- 手工插入一条 PENDING 后调 confirm，返回 `proposedType=null`、`updatedAt` 刷新，
  确认修复在运行中的应用生效。

根因补充：AgentScope 的 `OpenAIChatModel` 原先没有发送 `thinking: disabled`，
DeepSeek 把输出 token 花在推理上，导致正文在 1024 token 处被截断。
已在 `GenerateOptions` 显式传 `thinking`，与自建 `ChatClient` 行为对齐。

### 4.5 LightRAG 实机验证（2026-10-23）

- 用 `scripts/start-lightrag.ps1` 启动 LightRAG 1.5.7，`/docs` 200，服务正常监听 9621；
- `POST /documents/text` 需要 `file_source`（缺省会 400），适配器已补
  `recordandgrow://document/{id}`；
- 插入测试文本后 `track_status` 从 `processing` 变 `processed`，
  `POST /query` 返回 200 且 `llm_generated=true`，说明 DeepSeek + DashScope key 均有效；
- 第二个应用实例（8081，`GRAPH_STORE=lightrag`）验证：
  `reindex?limit=2` → `indexed=2`；`graph/search?q=Kafka` 返回 LightRAG prose + references；
  `graph/stats` 的 nodes/edges 对 LightRAG 恒为 0（没有通过其 API 统计）。
- 主应用（8080）切到 LightRAG 后再次验证 `reindex` / `graph/search`；
  发现每条 reference 都复制整段 response，导致 5 条重复证据；已改为
  “一段回答一条命中 + references 去重成 title”；重启后 8080 复测：
  `graph/search?q=学习` → `hits=1`，`source=LightRAG`，`title=41, 38, 43`，`stats` 0/0（预期）。
- 降级：停掉 LightRAG（9621 拒绝连接）后，8080 的 `graph/search?q=学习`
  自动回落 Postgres，返回 `英语学习计划 -包含-> 目标`，接口不报错。

未执行：

- 文档 embedding / rerank 阈值校准；
- Neo4j 真实数据的完整 reindex + search（HTTP/Cypher 冒烟已过）。

## 5. 结论

| 项 | 状态 |
|---|---|
| 编译 | ✅ |
| 单测 | ✅ 355/0/0 |
| 全量（含真库） | ✅ 389/0/0 |
| AgentScope 协议链路 | ✅ WireMock + 真实 key |
| MCP 握手 / tools-call | ✅ 真实 Tomcat + 真实 key |
| Neo4j / LightRAG 适配器 | ✅ WireMock 协议验证；❌ 真实服务未起 |
| 真库 DDL/CRUD | ✅ V3/V4 + 34 个集成测试 |
| 真实模型回答质量 | ✅ 基础链路 + 文档引用 + 多轮通过；质量评估未系统化 |
| 前端 | 不在范围 |
