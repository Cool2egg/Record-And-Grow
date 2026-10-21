# 模块六验证结果（2026-10-21）

本文件区分三类内容：

1. **本会话已跑**（Linux 沙箱，JDK 21.0.12 + Maven 3.9.9，**无 Docker**）——贴真实输出；
2. **交本机执行**（需要 Docker / 真实 key / 运行中的服务）——给可直接复制的命令与期望输出，**不算通过**；
3. **未验证**——明确列出，不写「应该没问题」。

---

## 1. 环境与基线

沙箱探测（2026-10-21，分支 `module/doc-retrieval`，切自 `module/document-ingest` / `80fb173`）：

```text
mvn -v                Apache Maven 3.9.9, Java 21.0.12.1 (Ubuntu)
docker info           command not found（沙箱无 Docker）
git status --short    AM docs/learning/05-document-in/learning-notes.md（与本模块无关，未提交）
```

基线说明：`module-05` 的验证文档记的是「224/0（Skipped 2）」。本会话实测为
**226 / 0 fail / Skipped 4**——因为模块五之后又加了 `VectorDistanceProbeTest`（2 个），
无 Docker 时两个探针类共 4 个跳过。按「实际执行」算是 222 个。本模块判定标准照旧：
**旧用例一个不少、一个不放宽**，新用例只增不改。

---

## 2. 本会话已跑：编译与非集成测试

```bash
mvn -B test-compile
mvn -B -Dtest='!*IntegrationTest' test
```

最终结果（2026-10-21，第 5 步提交前）：

```text
[INFO] BUILD SUCCESS
[INFO] Tests run: 279, Failures: 0, Errors: 0, Skipped: 7
```

`Skipped: 7` = `VectorCastProbeTest`(2) + `VectorDistanceProbeTest`(2) + `DocumentSearchProbeTest`(3)，
全部因为**没有 Docker**，**不算通过**。

### 2.1 新增测试

| 测试类 | 数量 | 本会话结果 | 覆盖 |
|---|---|---|---|
| `QwenEmbeddingClientOverloadTest` | 4 | 4/0 | 接口默认方法委托旧 `embed`；旧方法仍按配置 2 次尝试；新重载 500 只发 1 次请求；新重载 200ms 超时截断（<1.5s） |
| `DocumentRetrieverTest` | 28 | 28/0 | 三态、`enabled=false`、空库不调 embedding、无 key → `NOT_CONFIGURED`；10 种 `AiErrorType` 穷举映射 7 种原因；维度/条数异常；阈值；每文档 ≤2；相邻块去重+顺延补位；平局三连排序；topK；`topScore` 取阈值前最高分（全灭时非空、空结果 null）；SQL 异常上抛；两次调用一致；scan-limit 与按次超时透传 |
| `ContextAssemblerDocumentTest` | 13 | 13/0 | 无文档与模块四逐字一致（messages/sources/promptChars 全等）；降级只多一行且 system 用 A；B 版 system；`D1..Dn` 连续；`items.length == included + documentIncluded == totalIncluded`；空 heading 省略；300 字截断；`\n【问题】` 注入不能伪造段落；文档区满时先丢低分块；**边界「刚好放得下 / 少 1 字符 → 回退 A」**；总预算不超；时间线为空时文档仍可用；固定输入两次一致 |
| `ChatControllerDocumentTest` | 5 | 5/0 | SSE `start → sources → delta → done`、`sources` 只发一次、T 前 D 后、`type=document` / `id=documentId` / `chunkId` / `title`；非流式 `retrieval` 新字段；降级 200 + `documentsDegraded=true` + 降级文案；无文档时形状与模块四一致；0 命中仍 `documentSearched=true` |
| `DocumentSearchProbeTest` | 3 | **Skipped** | 真表 + V2 迁移：6.1 SQL 的过滤/排序/LIMIT 正确性 + `EXPLAIN (ANALYZE, BUFFERS)` 打印计划 |
| `DocumentRetrievalIntegrationTest` | 5 | **未执行**（无 Docker） | 真 MyBatis + pgvector：READY/软删/embedding NULL/model 过滤、`<=>` 排序、scan-limit、平局 tie-break、`existsReady` |

### 2.2 旧测试：只改了 1 处

| 类 / 方法 | 改什么 | 判定逻辑 |
|---|---|---|
| `ChatControllerTest#setUp` | `new ChatService(...)` 增加一个 `DocumentRetriever` 参数，并 stub `notSearched()` | **没变**；7 个用例的断言、`verifyNoInteractions` 全部保留，7/7 通过 |

零改动且通过：`ContextAssemblerTest` 8/8、`ChatSseServiceTest` 7/7、`QwenEmbeddingClientTest` 3/3、
`TimelineRetrieverTest`、`TimelineEntryServiceSearchTest`、`GlobalExceptionHandlerTest`。
靠的是：旧 `assemble(retrieval, question, now)` 重载、`SourceItem` 6 参构造器、`RetrievalMeta` 11 参构造器、
`ChatProperties` 原地扩展。

---

## 3. 交本机执行（需 Docker）

以下命令**本会话无法执行**，不算通过。按「最早暴露问题优先」排序。

### 3.1 真表探针（先跑这个，读侧 SQL 跑不通就停）

```bash
docker compose up -d postgres
mvn -Dtest=DocumentSearchProbeTest test
```

期望：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

`explainShouldBeRecorded` 会把 `EXPLAIN (ANALYZE, BUFFERS, COSTS)` 的计划逐行打到控制台。
把输出抄到本文件 §3.1.1，**不做断言**：小表走顺序扫描是合法的。

若报 `operator does not exist: vector <=> character varying` 或 `42846`，**停下**，
不要改成字符串拼 SQL；按设计稿的退化方案（驱动 scope + `PGobject`）另开讨论。

#### 3.1.1 实际执行计划

**未验证**——本会话无 Docker。跑完 3.1 后粘贴到这里。

### 3.2 检索集成测试

```bash
mvn -Dtest=DocumentRetrievalIntegrationTest test
```

期望 `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`。覆盖：READY / 软删 / `PENDING` /
`embedding IS NULL` / 不同 `embedding_model` 的过滤；`<=>` 距离排序；`LIMIT`；同分按
`document_id, chunk_index` 的平局顺序；`existsReady` 忽略非 READY 与软删。

### 3.3 集成回归（含模块二时间线、模块五入库）

```bash
mvn -Dtest='*IntegrationTest' test
```

期望全绿，且 `DocumentIngestIntegrationTest`、`DocumentSchemaIntegrationTest`、
`TimelineEntryIntegrationTest` 的数量与本机模块五验证时一致（入库链路没被改动）。

### 3.4 非集成测试（本机，Skipped 应为 0）

```bash
mvn -Dtest='!*IntegrationTest' test
```

期望 `279 run / 0 fail / 0 skipped`（本机有 Docker，两个探针类会真跑）。

---

## 4. 交本机执行：手工验收

**全部未验证**。以下按设计稿 21.2 的十项，给命令与判定标准。

### 4.1 启动

```powershell
docker compose up -d postgres
docker compose run --rm flyway
$env:DEEPSEEK_API_KEY = "<真实 key>"
$env:DASHSCOPE_API_KEY = "<真实 key>"
$env:DOCUMENT_QUEUE_MODE = "local"
mvn spring-boot:run
```

### 4.2 上传并等 READY

```powershell
curl.exe -F "file=@D:\docs\docker-notes.md" http://localhost:8080/api/documents
# 轮询详情，直到 status=READY
curl.exe http://localhost:8080/api/documents/<id>
```

### 4.3 只有文档能回答的问题

```powershell
curl.exe -N -X POST http://localhost:8080/api/chat/stream -H "Content-Type: application/json" `
  -d '{"question":"我上传的资料里，Docker 镜像加速怎么配？"}'
```

期望：

- 回答引用 `[D1]`（或 `[D2]`）；
- `event:sources` 里出现 `"type":"document"`，且 `chunkId`、`title`、`score` 非空、`startTime/endTime` 为 `null`；
- `"documentSearched":true`、`"documentIncluded">0`、`"totalIncluded" == "included" + "documentIncluded"`；
- 事件顺序 `start → sources → delta… → done`，`sources` 只出现一次。

样例形状（**示意，非本会话真实输出**）：

```json
{"items":[
  {"ref":"T1","type":"timeline","id":12,"startTime":"2026-10-19T20:00:00+08:00","endTime":null,
   "excerpt":"学习 pgvector 的 HNSW 索引…","chunkId":null,"title":null,"heading":null,"score":null},
  {"ref":"D1","type":"document","id":3,"startTime":null,"endTime":null,
   "excerpt":"配置 daemon.json 的 registry-mirrors 字段后重启 docker…",
   "chunkId":57,"title":"Docker 实践笔记","heading":"部署 > 镜像加速","score":0.812345}],
 "retrieval":{"matched":1,"retrieved":1,"included":1,"documentSearched":true,
   "documentsRetrieved":1,"documentIncluded":1,"totalIncluded":2,
   "documentsDegraded":false,"degradeReason":null}}
```

### 4.4 时间线问题仍只引时间线

`{"question":"我上周做了什么？"}` → 以 `[T…]` 为主；即使召回到文档块，也不能把资料说成用户经历。

### 4.5 混合问题

`{"question":"结合我的记录和资料，我该怎么配加速？"}` → `T` 与 `D` 都出现，编号与 sources 一一对应。

### 4.6 软删 / PENDING 文档

删掉文档后同一问题 → 不再出现 `[D…]`，无 500；上传后立刻（`PENDING`）提问 → 不引用它、不报错。

### 4.7 不配 embedding key

只配 `DEEPSEEK_API_KEY`，并保证库里有 READY 文档 → 提问仍 **200**，时间线正常；
`"documentsDegraded":true`、`"documentSearched":false`、`"degradeReason":"NOT_CONFIGURED"`；
Prompt 里有「【文档检索】本次不可用…」。**没有 READY 文档时**则应当是 `documentSearched=false, degraded=false`，Prompt 与模块四一致。

### 4.8 停库

```powershell
docker compose stop postgres
```

提问 → **503 / 50300**，且没有 `event:start`。

### 4.9 响应与日志隐私

响应体不得出现 `normalizedText`、`storagePath`、`embedding`；日志不得出现文档正文、标题、key、向量。
日志应能看到 `documentSearched / documentsRetrieved / documentIncluded / documentsDegraded /
degradeReason / queryEmbeddingMs / documentSqlMs / documentRetrievalMs / topScore(4 位) /
documentsBudgetTruncated`。

### 4.10 阈值校准（可选，按设计稿 8.3）

准备 3–5 份真实文档、10 个相关问题 + 10 个不相关问题，记录日志里的 `topScore`（**阈值前**最高分），
取「不相关问题 95 分位」与「相关问题 5 分位」之间的空隙调整 `recordandgrow.chat.documents.similarity-threshold`，
不改代码。

---

## 5. 偏离设计稿 / 设计稿内部歧义的取舍

1. **`DocumentRetrievalResult` 多了 `queryEmbeddingMs` / `documentSqlMs` 两个字段。**
   设计稿第 18 节的结果字段清单只列了 hits + searched/degraded/reason/topScore，但 13.1 要求
   `ChatService` 的单行日志带上这两个耗时。耗时只能在这两个动作发生的地方测，因此放在结果里随结果返回。
   仅用于日志，不进 `sources`、不影响任何语义。工厂方法保留旧签名（填 0）。

2. **「embedding 返回条数 ≠ 1」归为 `BAD_RESPONSE`，不是 `DIMENSION`。**
   设计稿 8.2 写「条数 != 1 或长度 != dimensions() 或 dimensions() != 1024 → DIMENSION」，
   而 7.4/第 12 节/15.1 把「返回条数/索引异常」归为 `BAD_RESPONSE`、只有维度不符才是 `DIMENSION`。
   采用后者：条数异常是响应结构问题，维度不符才是配置/模型问题。两者都是降级，HTTP 行为一致。

3. **文档 `SourceItem.excerpt` 保留原始文本（不压空白）。**
   按 9.2「`SourceItem` 的 title/heading/excerpt 保留原始文本；归一化只作用于 Prompt」实现；
   时间线的 excerpt 仍走模块四的空白压缩，未改动。

4. **无其他偏离。** 未新增表/列/迁移/依赖；未改 V1/V2；未做关键词路/RRF/rerank/查询改写；
   SSE 事件顺序与请求体未变；`ChatControllerTest` 只改了构造参数那一处。

---

## 6. 未验证项清单（不写「应该能过」）

1. `DocumentSearchProbeTest` 3 个用例：真表 6.1 SQL 的过滤/排序/`LIMIT`。
2. **`EXPLAIN (ANALYZE)` 实际计划**（是否选 HNSW、后过滤剩多少行）。
3. `DocumentRetrievalIntegrationTest` 5 个用例：真 MyBatis 投影映射与 `cast(#{vector} AS vector)`。
4. `mvn -Dtest='*IntegrationTest' test` 全量回归（模块二、五未被影响）。
5. 本机 `mvn -Dtest='!*IntegrationTest' test` 的 Skipped=0 结果。
6. 全部十项手工验收（4.1–4.10）：真实上传、`[D…]` 引用、`type=document`、降级 200、停库 503、SSE 顺序。
7. **真实 Qwen 向量下的阈值 0.25 是否合适**（本模块只给校准方法，未做评测集）。
8. 端到端首包延迟（设计稿 13.3 预期：正常 +0.3–0.8s，最坏 8s 后降级）。
9. 删除/替换 embedding 模型后旧向量被过滤的实际表现（逻辑已由集成测试的 `embedding_model` 过滤覆盖）。
