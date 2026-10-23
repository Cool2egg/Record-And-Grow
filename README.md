# RecordAndGrow

一个「懂你的个人上下文对话 Agent」：基于个人时间线、文档、知识图谱和长期记忆回答问题，并给出结合个人习惯的建议。

- **可运行**：无 API key 也能启动；没配 key 时自动回落到固定双路检索，对话接口返回明确错误而不是崩溃。
- **可学习**：每个模块有设计稿、验证记录与学习笔记。
- **可展示**：Java 21 + Spring Boot 4 的工程底子 + AgentScope 编排 + MCP / Skills。

## 架构

```
Controller
  ├─ ChatController ──► ChatService ──► AgentChatService（AgentScope，有 key 时）
  │                         └──────────► 模块四固定链路（无 key / 关闭 Agent 时）
  ├─ Timeline / Document / Memory / Graph / Skill / Health
  └─ MCP Server（/mcp，stateless HTTP）

AgentChatService
  ├─ 检索 Agent：ReActAgent + Toolkit
  │    ├─ search_timeline / get_timeline_by_date  → TimelineRetriever
  │    ├─ search_documents                        → 向量 + 关键词 + RRF + Rerank
  │    ├─ search_memory                           → memory 表（仅 FACT / INFERRED）
  │    ├─ search_graph                            → graph_node / graph_edge
  │    └─ propose_memory                          → PENDING（用户确认后才生效）
  └─ 回答 Agent：无工具，基于证据流式生成，正文带 [T]/[D]/[M]/[G] 编号

存储：PostgreSQL（pgvector + 业务表 + 知识图谱两张表）；图谱可选 Neo4j / LightRAG，失败自动回落
可选：Elasticsearch（关键词通道）、RocketMQ（文档异步入库）、DashScope Rerank
```

## 技术栈

- Java 21 / Spring Boot 4.0.8 / Maven
- MyBatis-Plus + PostgreSQL + pgvector
- 自建 `ChatClient` / `EmbeddingClient`（DeepSeek / Qwen OpenAI 兼容接口）+ SSE
- **AgentScope Java 2.0.4**（ReAct、Toolkit、MCP 客户端能力）
- 官方 MCP Java SDK 0.17.2（stateless HTTP Server）
- Apache Tika（PDF / Markdown / TXT）、RocketMQ、Elasticsearch（可选）
- JUnit 5 / Testcontainers / WireMock

## 快速开始

前置：JDK 21、Maven 3.9+；可选 Docker（Postgres / ES / RocketMQ）。

```bash
# 1) 起依赖（只跑对话可以只起 postgres）
docker compose up -d postgres
docker compose run --rm flyway

# 可选：启用 Neo4j 图谱（默认 Postgres 图，不需要）
docker compose --profile graph up -d neo4j
export GRAPH_STORE=neo4j NEO4J_PASSWORD=recordandgrow

# 2) 配 key（不配也能启动，Agent 路径会自动关闭）
export DEEPSEEK_API_KEY=sk-...
export DASHSCOPE_API_KEY=sk-...        # 文档 embedding / rerank 需要

# 3) 启动
mvn spring-boot:run
```

默认端口 `8080`。健康检查：`GET /api/health`。

### 关键开关

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_ENABLED` | `true` | 允许 AgentScope 编排；仍需 `DEEPSEEK_API_KEY` |
| `MEMORY_ENABLED` | `true` | 会话记忆 + 长期记忆 |
| `MEMORY_EXTRACTION_ENABLED` | `true` | 回答后异步提炼候选记忆 |
| `GRAPH_ENABLED` | `true` | 知识图谱抽取与检索 |
| `GRAPH_STORE` | `postgres` | `postgres` / `neo4j` / `lightrag`；外部存储失败自动回落 |
| `NEO4J_URL` | `http://localhost:7474` | Neo4j HTTP 端点（`GRAPH_STORE=neo4j` 时） |
| `NEO4J_PASSWORD` | 空 | Neo4j 密码 |
| `NEO4J_USERNAME` / `NEO4J_DATABASE` | `neo4j` / `neo4j` | Neo4j 账号与库名 |
| `LIGHTRAG_URL` | `http://localhost:9621` | LightRAG Server（`GRAPH_STORE=lightrag` 时） |
| `LIGHTRAG_QUERY_PATH` / `LIGHTRAG_INSERT_PATH` | `/query` / `/documents/text` | LightRAG 接口路径，按服务端版本可改 |
| `LIGHTRAG_MODE` | `hybrid` | LightRAG 检索模式 |
| `LIGHTRAG_API_KEY` | 空 | LightRAG Server 开了鉴权时填同一个值 |
| `MCP_ENABLED` | `true` | `/mcp` 只读 MCP Server |
| `SKILLS_ENABLED` | `true` | 扫描 `./skills` 注入技能指引 |
| `ROUTING_ENABLED` | `false` | 旧链路的 LLM 意图路由（Agent 路径不需要） |

## 可选：Neo4j / LightRAG

默认 `GRAPH_STORE=postgres`，不需要下面任何服务。切换后图谱数据不共享，切换完要重新 `reindex`。

### Neo4j

```powershell
# 1) 起 Neo4j（compose 里已带 profile）
docker compose --profile graph up -d neo4j
# 浏览器：http://localhost:7474，账号 neo4j / recordandgrow

# 2) 在 IDEA Run Configuration 的环境变量里加：
# GRAPH_STORE=neo4j
# NEO4J_URL=http://localhost:7474
# NEO4J_DATABASE=neo4j
# NEO4J_USERNAME=neo4j
# NEO4J_PASSWORD=recordandgrow

# 3) 重启应用，然后重建图（一次最多 10 篇，重复调用直到 indexed 变少）
curl.exe -X POST "http://127.0.0.1:8080/api/graph/reindex?limit=10"
curl.exe "http://127.0.0.1:8080/api/graph/stats"
curl.exe "http://127.0.0.1:8080/api/graph/search?q=学习"
```

验证：

```cypher
MATCH (n:RecordAndGrow) RETURN n LIMIT 25;
MATCH ()-[r:RELATED]->() RETURN r LIMIT 25;
```

降级：`docker compose --profile graph stop neo4j` 后再搜，应用日志会打
`graph store neo4j ... fallback to postgres`，接口继续返回。

### LightRAG

LightRAG 是独立的 Python 服务，首次启动会自动下载依赖（uvx 会缓存）。

```powershell
# 方式 A：当前会话直接设 key（不要写进脚本/仓库）
$env:DEEPSEEK_API_KEY="sk-..."
$env:DASHSCOPE_API_KEY="sk-..."

# 方式 B：写入 scripts/lightrag.env（已被 .gitignore，不会提交）
Copy-Item .\scripts\lightrag.env.example .\scripts\lightrag.env
notepad .\scripts\lightrag.env

# 启动 LightRAG（脚本会设好 LLM/Embedding 的 OpenAI 兼容配置）
powershell -ExecutionPolicy Bypass -File .\scripts\start-lightrag.ps1

# 验证
# Swagger: http://localhost:9621/docs
# WebUI:   http://localhost:9621/webui
```

`LIGHTRAG_API_KEY` 是 LightRAG **Server 自身**的接口鉴权 key，不是模型 key；
不设就不鉴权。设了的话，上面 IDEA 环境变量里也要加同一个 `LIGHTRAG_API_KEY`。

然后在 IDEA Run Configuration 加：

```
GRAPH_STORE=lightrag
LIGHTRAG_URL=http://localhost:9621
LIGHTRAG_QUERY_PATH=/query
LIGHTRAG_INSERT_PATH=/documents/text
LIGHTRAG_MODE=hybrid
```

重启应用后 `POST /api/graph/reindex?limit=10`。注意 LightRAG 入库是**异步**的：
`/documents/text` 返回后还要等它抽取完（WebUI 或 `/documents` 能看到状态），
再调 `/api/graph/search` 才有结果。若你的 LightRAG 版本接口路径不同，以 `/docs` 为准，
用 `LIGHTRAG_QUERY_PATH` / `LIGHTRAG_INSERT_PATH` 覆盖。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/stream` | SSE 对话：`start → sources → delta… → done/error` |
| POST | `/api/chat` | 一次性回答（含 `conversationId`） |
| GET/POST/PUT/DELETE | `/api/timeline` | 时间线 CRUD |
| POST | `/api/documents` | 上传文档（PDF/MD/TXT） |
| GET | `/api/documents`、`/api/documents/{id}/chunks` | 文档列表 / 分块 |
| GET/POST | `/api/conversations` | 会话列表 / 新建 |
| GET | `/api/conversations/{id}/messages` | 会话消息 |
| GET/POST | `/api/memories` | 记忆列表 / 手工新增 |
| GET | `/api/memories/pending` | 待确认候选 |
| POST | `/api/memories/{id}/confirm` / `reject` | 确认 / 拒绝 |
| GET | `/api/graph/search?q=` | 图谱 1 跳检索 |
| POST | `/api/graph/reindex?limit=5` | 手工重建图谱 |
| GET | `/api/skills`、POST `/api/skills/reload` | 技能列表 / 热加载 |
| POST | `/mcp` | MCP stateless JSON-RPC（initialize / tools/list / tools/call） |

SSE 事件示例：

```
event: start
data: {"requestId":"...","conversationId":12}

event: sources
data: {"items":[{"ref":"T1","type":"timeline",...}],"retrieval":{...}}

event: delta
data: {"content":"你上周..."}
```

## 模块状态

| 模块 | 状态 | 说明 |
|---|---|---|
| 1 项目骨架 | ✅ | 统一响应 / 异常 / 健康检查 |
| 2 时间线 | ✅ | CRUD + 区间重叠查询 |
| 3 AI 接入 | ✅ | ChatClient / EmbeddingClient / SSE / 重试降级 |
| 4 最小闭环 | ✅ | 时间线检索 + Prompt + 生成 |
| 5 文档入库 | ✅ | Tika + 分块 + 向量 + MQ / 本地队列 |
| 6 文档检索 | ✅ | 三态降级 + 预算 |
| 7 知识图谱 | ✅ | Postgres 图 + LLM 抽取 + 1 跳检索；可选 Neo4j/LightRAG，失败自动回落（见设计稿） |
| 8 意图识别与路由 | ✅ | 旧链路 LLM 路由；Agent 路径由工具选择取代 |
| 9 RRF + Rerank | ✅ | 向量 + 关键词融合、证据闸门 |
| 10 记忆系统 | ✅ | 会话摘要 + 三态长期记忆 + 工具/接口 |
| 11 Agent 编排 | ✅ | AgentScope 两段式 ReAct + SSE 桥接 |
| 12 ReAct / MCP / Skills | ✅ | ReAct、MCP Server、Markdown Skills |
| 13 前端 | ⏳ | 不在本轮范围 |

## 测试

```bash
mvn test                     # 单测 + WireMock，不需要 Docker
mvn -Pintegration test       # 额外跑 Testcontainers 真库测试（需要 Docker）
```

当前单测覆盖：AI 客户端、SSE、检索、融合、文档入库、路由、记忆、图谱、Agent 编排、MCP Server、Skills。

## 文档

- 设计：`docs/design/overview.md`、`docs/design/module-07-knowledge-graph.md`、`module-10-memory.md`、`module-11-agentscope.md`、`module-12-mcp-skills.md`
- 验证：`docs/verification/`
- 已知问题：`docs/known-issues.md`
- 学习笔记：`docs/learning/`

## 已知限制

- Agent / 总结 / 记忆提炼都依赖真实 key；没有 key 时只验证旧链路与降级路径。
- 图谱默认用 Postgres，不引 Neo4j；实体抽取依赖 LLM，抽取失败不会清空旧图。
- MCP Server 是 stateless 且无鉴权，默认只应监听本机或放在内网。
- 单实例假设：摘要与对账任务没有分布式锁。
