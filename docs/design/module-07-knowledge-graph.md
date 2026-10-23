# 模块七：知识图谱

> 目标：让「A 和 B 有什么关系」「这个术语属于哪个体系」这类问题能从文档的实体关系里找答案，而不是只靠相似度块。

## 1. 设计选择：默认 Postgres，Neo4j / LightRAG 可选

默认实现是 **Postgres 两张表 + 1 跳查询**，原因：

- 单用户、几十篇文档、几千条边，图查询就是「实体名 LIKE + 一跳」，Postgres 足够；
- 少一个中间件，就少一套部署、备份、健康检查与故障面；
- 知识图谱在本项目里是**文档检索的补充证据**，不是独立主链路，可用性要求低于 Postgres。

同时保留两条外部存储路径，按配置切换：

| `recordandgrow.graph.store` | 实现 | 说明 |
|---|---|---|
| `postgres`（默认） | `JdbcGraphStore` | 两张表，零外部依赖 |
| `neo4j` | `Neo4jGraphStore` + 回落 | HTTP `/db/{database}/tx/commit`，不引 Driver |
| `lightrag` | `LightRagGraphStore` + 回落 | `/documents/text` 写文本、`/query` 取回答 + references |

外部存储统一包在 `FallbackGraphStore` 里：HTTP 超时、连接拒绝、协议错误都会回落到 Postgres；
启动阶段不连接外部存储，所以没有 Neo4j/LightRAG 也能正常启动。

## 2. 数据模型（Postgres，V4）

```sql
graph_node(id, name, normalized, type, document_id, created_at)
graph_edge(id, source_id, target_id, relation, document_id, weight, created_at)
```

- `normalized = lower(trim(压空白))`，用于匹配与去重；
- 每篇文档的图是**派生数据**：重建时先 `DELETE ... WHERE document_id = ?` 再插入，不做增量；
- `document_id` 外键级联删除；文档软删时由 `GraphIndexer.deleteDocument` 显式清理（软删不触发 FK）；
- 索引：`(document_id, normalized)` 唯一、`normalized`、边的 source/target。

## 3. 写入：文档 READY 后异步抽取

```
DocumentIngestService.markReady
  └─ GraphIndexer.indexAsync(documentId)        // graphExecutor 单线程
       ├─ 文档不是 READY / 正文为空 → 跳过
       ├─ GraphExtractor.extract(title, text)   // 一次 LLM 调用，JSON 数组
       └─ GraphStore.replaceDocument(...)
```

抽取 Prompt 约束：只用文档明确支持的关系、实体用原词、最多 15 条、忽略目录与免责声明。

失败语义：
- 没有 key → 直接跳过，不记录错误；
- 抽取失败 / JSON 不合法 → 返回空列表，**不清空旧图**（避免一次模型抖动删掉已有知识）；
- 手工重建：`POST /api/graph/reindex?limit=5`。

## 4. 读取：关键词 → 实体 → 1 跳

```
问题 → 关键词（引号/ASCII）+ 连续汉字 2 字滑窗 → GraphStore.search
     → 命中 source 或 target 的三元组（按 weight 倒序，limit 5）
```

不做多跳展开：两跳很容易把无关实体串进来，而本项目没有实体消歧与关系方向推理。

中文没有分词器，2 字滑窗会带来噪声。这是有意的取舍，不是「觉得它足够好」：

- 图是辅助通道，单用户规模下多一个 HanLP/jieba 词典或 IK 服务，依赖与启动成本高于收益；
- 实体是 LLM 抽取出来的短语（「英语学习计划」「注意力」），不是任意中文词，2 字滑窗对未登录词反而有召回；
- 滑窗只用于 Postgres / Neo4j 的实体名 `LIKE`；LightRAG 需要完整问句，已通过
  `GraphStore.search(question, terms, limit)` 直接传原问题，不能把滑窗碎片拼成 query；
- 代价是停用词噪声（「什么」「关系」）和 8 个词的上限；图命中不了就返回空，不会污染回答。
  真要把图变成主链路时，再引入 HanLP / Lucene SmartCN 或复用 ES 的 IK 分词器，并用评测集校准。

## 5. 对外形态

- REST：`GET /api/graph/search?q=`、`POST /api/graph/reindex`、`GET /api/graph/stats`；
- Agent 工具：`search_graph(query)`，结果以 `[G1] A --关系--> B（出处《标题》）` 进入证据区；
- SSE `sources`：`type=graph`，`id` 为 edge id，`title` 为出处文档，`excerpt` 为三元组文本；
- `RetrievalMeta.graphIncluded` 记录纳入条数。

## 6. 为什么旧链路不接图谱

旧链路（无 key 时的固定双路）保持「时间线 + 文档」两路，不引第三路：

- 图谱是从文档派生的，文档已经查过时再查图谱，收益主要是关系型问题；
- 旧链路是降级路径，要求确定性；图谱依赖 LLM 抽取，天然不确定；
- 真正需要图谱的是 Agent 路径，工具选择天然支持「按需查」。

## 7. 测试

- `GraphExtractorTest`：围栏 JSON、非法三元组、上限；
- `GraphRetrieverTest`：禁用态、关键词生成、命中映射；
- `Neo4jGraphStoreTest`：Cypher 请求形状、Basic Auth、行解析、count；
- `LightRagGraphStoreTest`：references 映射、无 references 回落、insert 请求、500 异常；
- `FallbackGraphStoreTest`：主存储失败回落、两边都失败上抛；
- `GraphStoreSelectionTest`：`store=neo4j` 时启动期只装配客户端、不连接；
- `MemoryPersistenceIntegrationTest`（`-Pintegration`）：V4 建表、写入、1 跳查询、出处文档。

## 8. 外部存储适配细节

### Neo4j

- 不引 Driver：只用 HTTP 事务端点 `/db/{database}/tx/commit`；
- 图模型：
  ```cypher
  (:RecordAndGrow:Entity {documentId, normalized, name})
  (:Entity)-[:RELATED {documentId, relation, title}]->(:Entity)
  ```
- 写入：先 `MATCH ... DETACH DELETE` 清掉该文档的旧图，再逐条 `MERGE` 节点与关系；
  所有语句放在一个事务里，避免半成品；
- 检索：`UNWIND $terms` + `s.normalized CONTAINS term OR t.normalized CONTAINS term`，
  返回 `elementId(r)`、两端实体名、关系、documentId、title；
- 认证：username/password → Basic Auth；密码为空时仍发用户名（Neo4j 默认要求改密码）。

### LightRAG

- 写入：把三元组拼成 `source relation target` 文本，POST `/documents/text`，
  body `{text, description, file_source}`；`file_source` 用
  `recordandgrow://document/{id}`，LightRAG 1.5.x 缺它会 400；路径可用 `LIGHTRAG_INSERT_PATH` 覆盖；
- 检索：POST `/query`，body `{query, mode}`；解析 `response` 与 `references`：
  - `response` 非空：整段回答只生成**一条** `GraphHit`，title 取 references 的
    `file_path` 去重后前三个；不能每个 reference 复制一遍回答，否则 5 个引用会变成
    5 条重复证据；
  - `response` 为空但有 references：每条 reference 的 `content` 一条 `GraphHit`；
- 可选鉴权：`LIGHTRAG_API_KEY` 非空时带 `X-API-Key`；
- LightRAG 没有稳定的按 documentId 删除 API，`deleteDocument` 是 no-op；
  重建时以新写入为准，旧内容清理交给 LightRAG 自己的维护流程；
- LightRAG 入库是异步的：写入成功后要等它抽取完成，`/query` 才有结果。

### 配置

```yaml
recordandgrow:
  graph:
    store: ${GRAPH_STORE:postgres}
    neo4j:
      url: ${NEO4J_URL:http://localhost:7474}
      database: ${NEO4J_DATABASE:neo4j}
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:}
      timeout: 3s
    lightrag:
      url: ${LIGHTRAG_URL:http://localhost:9621}
      query-path: ${LIGHTRAG_QUERY_PATH:/query}
      insert-path: ${LIGHTRAG_INSERT_PATH:/documents/text}
      mode: ${LIGHTRAG_MODE:hybrid}
      api-key: ${LIGHTRAG_API_KEY:}
      timeout: 5s
      max-response-chars: 800
```

### 降级语义

- 外部存储调用抛任意 `RuntimeException` → `FallbackGraphStore` 记录 WARN 并走 Postgres；
- 回落也失败 → `GraphRetriever` 捕获并返回 `degraded=true`，回答继续；
- `GraphIndexer` 的写失败只丢图，不影响文档入库；
- 默认 `postgres` 时没有额外网络调用，也没有外部依赖。
