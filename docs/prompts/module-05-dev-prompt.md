# 开发任务：RecordAndGrow 模块五「文档入库」

你是这个仓库的实现者。先读环境和必读清单，再动手；不要在没读代码的情况下猜 API、猜版本号。

---

## 0. 环境与起点

- 仓库：`D:\RecordAndGrow`（Windows 11，PowerShell）
- 技术栈：Java 21 + Spring Boot 4.0.8 + MyBatis-Plus 3.5.17 + PostgreSQL 16（pgvector）+ Flyway
- **不用** Spring AI / LangChain4j / Lombok / MapStruct；DTO 手写 getter/setter，构造器注入
- 当前分支：`module/closed-loop`，HEAD = `chore(release): v0.1.0`；模块一到四已全部提交
- 新分支：从当前 HEAD 切 `module/document-ingest`
- Docker 可用（Testcontainers 要它）；`mvn` 已配好（3.9.16 + JDK 21），命令见 `docs/dev-environment.md`
- **受限沙箱里写 `target/`、`.git/`、`src/` 都会被拒**（已有目录缺权限项，新建目录正常）。本任务需要跑 Maven 和 git，**要求用完全权限的会话**；若当前是受限模式，先停下来告诉用户，不要假装跑通
- **跑不了测试就说跑不了**，写进验证文档；不要声称通过

---

## 1. 必读（按顺序）

1. `docs/design/overview.md` —— 产品原则与模块规划
2. `docs/design/module-05-document-ingest.md` —— **本模块的权威方案**。与任何口头描述冲突时以它为准
3. `docs/known-issues.md` —— 已接受的限制，不要顺手"修"
4. 代码（照抄约定，不要另立风格）：
   - `src/main/resources/db/migration/V1__create_timeline_entry.sql` —— 迁移写法
   - `src/main/java/com/recordandgrow/timeline/` —— entity / mapper / service / dto 四件套的完整样子
   - `src/main/java/com/recordandgrow/ai/EmbeddingClient.java` 与 `ai/qwen/QwenEmbeddingClient.java` —— 你要复用的接口（内部已按 10 条分批，**不要在外面再分批**）
   - `src/main/java/com/recordandgrow/ai/config/AiProperties.java` —— `getBatchSize() / getDimensions() / getModel()`
   - `src/main/java/com/recordandgrow/infra/exception/{ErrorCode, BusinessException, GlobalExceptionHandler}.java`
   - `src/main/java/com/recordandgrow/infra/response/{ApiResponse, PageResult}.java`
   - `src/main/java/com/recordandgrow/config/MybatisPlusMetaObjectHandler.java` —— createdAt/updatedAt 自动填充
   - `src/test/java/com/recordandgrow/timeline/TimelineEntryIntegrationTest.java` —— Testcontainers + 程序化 Flyway 的既有做法
   - `src/main/java/com/recordandgrow/ai/sse/ChatSseService.java` —— 虚拟线程、执行器、关闭的既有风格
   - `src/main/java/com/recordandgrow/config/ChatProperties.java` + `config/ChatConfig.java` —— 配置类怎么挂

---

## 2. 已定决策（不要重新讨论）

> 2026-10-20 已确认：原生 `rocketmq-client`、双 topic、jtokkit、不做 Dashboard。设计稿第 13 节有理由。

1. **只支持 PDF / Markdown / TXT**。docx、OCR、网页抓取都不做（写进「不做」）。
2. **异步走 RocketMQ 两阶段**：`doc-ingest-parse` → `doc-ingest-embed` 两个 topic、两个消费组；`POST /api/documents` 只做接收 + 校验 + 落盘 + 建记录 + 发消息，返回 202。
3. **状态机**：`PENDING → PARSING → EMBEDDING → READY`；任一步失败由 MQ 重试 3 次，仍失败 → `FAILED` + `error_message`（≤500 字）。
4. **原文存本地目录**（`recordandgrow.document.storage-dir`，默认 `./data/documents`），DB 只存 sha256、路径、元信息；`data/` 加进 `.gitignore`。留 `DocumentStorage` 接口，实现只有本地文件一种。
5. **去重**：内容 sha256 + **部分唯一索引 `WHERE deleted = FALSE`**；重复上传返回已有文档（200，`deduplicated=true`），不重复解析。
6. **向量列 `vector(1024)`**，写入用 `cast(#{embedding} as vector)`，参数传 `"[0.1,0.2,...]"` 字符串；**不要改 `postgresql` 依赖的 scope**，不要引 `PGobject`、不要引 `com.pgvector`。
   - 序列化 float 必须用 `Locale.ROOT`，否则某些 Locale 下小数点会变逗号
   - ⚠️ 这是本模块唯一的 `[推断]` 点：**先在真库上验一次 cast 写法能跑通，再继续**。跑不通就先停下来报告，不要自己改方案
7. **`CREATE EXTENSION IF NOT EXISTS vector;` 写进 V2 迁移第一句**，不要依赖 `docker/init/`（Testcontainers 不跑 init 脚本）。
8. **分块**：`max-tokens=400` / 重叠 60 token / 最小 20 token / 单文档上限 2000 块；Markdown 带标题面包屑，`heading` 单独存一列，embedding 输入 = `heading + "\n" + content`，返回给前端的 `content` 只有正文。
9. **偏移按 code point 计**（与 PG `char_length` 一致），Java 侧用 `offsetByCodePoints` 换算，禁止直接用 UTF-16 下标。
10. **chunk 先落库（`embedding = NULL`），再按批回填**；每批一个独立事务；重跑只补 `embedding IS NULL` 的行。
11. **绝不在事务里调 `embeddingClient.embed()`**；`@Transactional` 只包「改状态」或「写一批 chunk」。
12. **本模块不接入对话**：`ChatService`、`ContextAssembler`、`ChatController`、`SseEvents` 一行都不改。检索是模块六。
13. **队列抽象成接口**：`DocumentIngestQueue` + `RocketMqIngestQueue`（生产）/ `LocalIngestQueue`（测试、无 MQ 调试）。**所有自动化测试只走 local，绝不连 broker**。不引 Redis / MinIO。
14. **上传前预检 `embeddingClient.isConfigured()`**，未配置直接 503/50301，不落盘、不建记录。该接口目前没有这个方法，加一个 `default` 方法，`QwenEmbeddingClient` 覆写成「key 非空」——注意别改坏模块三的测试。
15. **分词器**：`TokenCounter` 接口 + jtokkit `cl100k_base` 实现（近似，比 Qwen 多算 1.5–2 倍，偏差方向是「块更小」，安全）；`Chunker` 是纯函数，不依赖 Spring、不碰 IO。
16. **service 必须有同步入口** `ingestParse(id)` / `ingestEmbed(id)`；MQ 监听器只负责「调入口 + 异常转重试」，不写业务。
17. **`@Transactional` 不能靠自调用**：拆出独立的 `DocumentChunkWriter` Bean，`ingest()` 调它来获得「每批独立事务」。不许用自注入代理。
18. **重复上传撞唯一索引**要捕获并回查返回已有文档（200），不能漏成 500。注意 MyBatis-Plus 是否做了 Spring 异常翻译——先确认抛的是 `DuplicateKeyException` 还是 `PersistenceException`，再决定 catch 哪个。
19. **同一文档处理中再次 reindex → 409**（新增 `DOCUMENT_BUSY(40900, 409)`），不排队、不并发跑。
20. **删除与入库互斥**：处理中的文档删除要 409 或先置 `FAILED`；后台任务每次写库前用 `deleted = false` 二次确认，发现已删就静默退出。
21. **`document.normalized_text` 存归一化全文**（设计稿 14.1）：`reindex` 直接读它、不再过 Tika，`char_start/char_end` 才有实际含义。**不得出现在任何响应 DTO 里**。
22. **对账看 `last_progress_at`，不是 `updated_at`**：状态切换和每批 embedding 写完都要刷心跳。用 `updated_at` 会把正在跑的大文档误判成卡住，重发 parse 会清空已回填向量。
23. **发消息在事务提交之后**：`create()` 只落库，controller 依次调 `create()` 和 `queue.sendParse()`。去重命中且 `status=PENDING` 时补发一次，否则「上次发送失败 + 这次去重」会永久卡住。
24. **`LocalIngestQueue` 内部走可注入的 `Executor`**（默认虚拟线程），测试注入 `Runnable::run`；不要做成同步阻塞，否则 local 模式演示大文件会占住 HTTP 线程。

> 若「对齐轮」对这些点给出了不同结论，以对齐结论为准，并在验证文档里记一句为什么改。

---

## 3. 硬约束

- **不许改模块四的行为**。现有 120 个非集成测试必须原样通过；一个断言都不许改。真要改，先停下来说明理由。
- **`application.yml` 只加键，不改既有键的值**。
- **新增依赖只允许三个**：Tika（解析）、`org.apache.rocketmq:rocketmq-client`（MQ）、jtokkit（token）。版本自己查当时最新稳定版，不要凭记忆写；引入后 `mvn dependency:tree` 看一眼体积。
- **响应体里不许出现** `storagePath`（宿主机路径泄露）、`embedding`（1024 个 float 不该进 JSON）。
- **分页接口复用** `PageResult.of(items, total, page, size)`，响应统一 `ApiResponse<T>`。
- **DTO 与 entity 分离**，entity 不直接出接口。
- **代码注释用中文**，解释「为什么」不是「是什么」；测试方法名用英文（照 `createAndGetShouldRoundTrip` 的风格），类注释中文。
- **每次只做一个可验证的步骤**，不要一口气写完六个步骤再跑测试。

---

## 4. 实施顺序（六步，每步一个提交）

### 开工前置（必须）

1. 先按 `docs/prompts/module-05-alignment-prompt.md` 完成一轮对齐，把结论写进回报；
2. **对齐结论被确认后**，才从第 0 步开始；
3. **每一步做完就停下来等确认**，不要连续做两步。上一步的测试没绿，不许开下一步。

### 第 0 步：提交设计稿

以下三个未跟踪路径先一起提交（`git status` 确认）：

```
docs/design/module-05-document-ingest.md   设计稿 v2.1
docs/dev-environment.md                     环境备忘
docs/prompts/                               对齐与实施提示词
```

提交信息：`docs(design): 模块五文档入库设计方案`

然后切分支 `module/document-ingest`。

### 第 1 步：迁移 + 实体 + Mapper

- `V2__create_document.sql`：建 `document` / `document_chunk`、扩展、四个索引，**照抄设计文档第 2 节的 DDL**
- `document/Document.java`、`document/DocumentChunk.java`（照 `TimelineEntry` 的注解风格；`document` 用 `@TableLogic` 软删，`document_chunk` **不要**软删，派生数据物理删）
- `document/DocumentMapper.java`、`document/DocumentChunkMapper.java`（照 `TimelineEntryMapper` 的注解方式）
- `config/DocumentProperties.java` + 挂到 `config/ChatConfig.java` 同款位置
- 测试 `DocumentSchemaIntegrationTest`：扩展存在；`vector_dims` = 1024；HNSW 索引存在；部分唯一索引「活跃行冲突、软删后可重传」

**完成标准**：`mvn -Dtest=DocumentSchemaIntegrationTest test` 绿（需 Docker）。

### 第 2 步：分块器（不碰 IO）

- `document/chunk/Chunker.java` + `Chunk` record（`content / heading / charStart / charEnd`）
- 归一化按设计文档 4.2：CRLF、行尾空白、连续空行压到 2、trim；**不合并行内空格**
- 递归切分：段落 → 句末标点 `。！？；!?;` → 按 code point 硬切
- 贪心合并 + 重叠对齐句首 + 小段过滤 + maxChunks 截断标记
- `ChunkerTest`，至少覆盖设计文档 10.1 列的 14 种情况；再加一条**确定性**断言（同输入两次结果完全相等）

**完成标准**：纯单测，无 Docker、无 Spring 上下文，全绿。

### 第 3 步：解析 + 存储

- `pom.xml` 加 Tika（`tika-parsers-standard-package`）
- `document/parse/DocumentParser.java`：`AutoDetectParser` + `ToTextContentHandler`；MIME 用 Tika 嗅探，不信 `Content-Type` 和扩展名；解析放 `Future.get(parse-timeout)`，超时取消 → `DOCUMENT_PARSE_FAILED`
- 文本 < 50 字 → 抛业务异常，文案含「可能是扫描件或空文档」
- `document/storage/DocumentStorage`（`store(InputStream, sha256, ext) → path`、`delete(path)`）+ `LocalDocumentStorage`
- fixture 放 `src/test/resources/fixtures/`：`sample.txt`、`sample.md`、一页 `sample.pdf`、`broken.pdf`、把 PDF 改名成 `.txt` 的伪装文件
- `DocumentParserTest` 覆盖：三种格式、损坏文件、空文件、伪装扩展名、超长截断

**完成标准**：parser 单测绿；坏文件抛的是业务异常而不是 500。

### 第 4 步：入库全链路（local 模式）

- `document/DocumentIngestService.java`：`register / ingestParse / ingestEmbed / reindex / delete / list / get / chunks`
- `DocumentIngestQueue` 接口 + `LocalIngestQueue`（同步直调，测试与无 MQ 演示用）
- `DocumentChunkWriter` 独立 Bean：每批 embedding 一个事务（**不许自调用**）
- `EmbeddingClient.isConfigured()` 默认方法 + Qwen 覆写
- 幂等：parse 开头查 status/deleted；先 `delete` 旧 chunk 再插；embed 只补 `embedding IS NULL`；`update ... where embedding is null` 双重保险
- 心跳：`markParsing` / `markEmbedding` / `markReady` 各刷一次 `last_progress_at`，**每批写完再刷一次**
- `DocumentIngestServiceTest`（mock，≥10）：状态机、幂等（同一消息消费两次结果一致）、断点续跑、retry_count 封顶
- `DocumentEmbeddingWireMockTest`：25 块 → 10/10/5；乱序对位；429 重试；维度不符不写库
- `DocumentIngestIntegrationTest`：真实 pgvector + WireMock + `LocalIngestQueue`。**不加 `@Transactional`**（分批提交会被测试事务吞掉），每例后手工清理

**完成标准**：`ingestParse` + `ingestEmbed` 跑完 `READY`；重复调用结果不变；第 2 批失败时第 1 批已落库。

### 第 5 步：接口层（仍用 local 模式，先能 curl）

- `ErrorCode` 加 `DOCUMENT_BUSY(40900,409)`、`PAYLOAD_TOO_LARGE(41300,413)`、`UNSUPPORTED_MEDIA_TYPE(41500,415)`、`DOCUMENT_PARSE_FAILED(42200,422)`、`QUEUE_UNAVAILABLE(50302,503)`
- `GlobalExceptionHandler` 加 `MaxUploadSizeExceededException` → 413、`MultipartException` → 400
- `application.yml` 加 `spring.servlet.multipart.*` 与 `recordandgrow.document.*`（照设计文档第 9 节）
- `controller/DocumentController` + `document/dto/*`
- 并发守卫：处理中 reindex / 删除 → 409；重复上传撞唯一索引 → 回查返回已有文档，且若它仍是 PENDING 就补发一次 `sendParse`
- **发消息写在 controller，不写进 `create()`**（事务提交后再发）；发送失败返回 503/50302，记录保留交给对账
- `DocumentControllerTest`（MockMvc，≥9，含 409/413/415/404 与「响应不含 embedding / normalizedText」）

**完成标准**：设计文档 10.5 的 curl 六项在 `queue.mode=local` 下通过。

### 第 6 步：RocketMQ 接入（唯一需要中间件的一步）

- `pom.xml` 加 `rocketmq-client`；**先跑一个只发一条、收一条的最小 demo**，同时 `mvn dependency:tree -Dincludes=io.netty` 确认 Boot BOM 有没有把它顶到 4.2（顶了就显式 pin 4.1.x）
- `document/queue/RocketMqIngestQueue`（producer）+ `DocumentParseConsumer` / `DocumentEmbedConsumer`（两个 push consumer）
- 启停走 `SmartLifecycle`（producer phase 0，consumer phase 100）；producer 启动失败只记日志 + `ready=false`，不阻断应用启动
- 监听器：`CONSUME_SUCCESS` / `RECONSUME_LATER`；用 `MessageExt.getReconsumeTimes()` 判断最后一次仍失败 → 标记 FAILED 后返回成功。**首次投递是 0 还是 1 用实测确认**，别照文档猜
- `docker-compose.yml` 加 namesrv + broker，`docker/rocketmq/broker.conf` 里必须配 `brokerIP1`，JVM 必须限内存
- 对账任务：启动一次 + 每 5 分钟，扫 `status in (...) and last_progress_at < now()-10min`，`retry_count < 3` 补发、否则 FAILED
- 手工验收：上传 → 两条 topic 各自的消费日志；kill 进程 → 消息重投；停 MQ → 上传返回 503/50302

**完成标准**：本机起 broker 后，`queue.mode=rocketmq` 下全流程通；`LocalIngestQueue` 相关测试仍然全绿。

### 第 7 步：文档收尾

- `docs/verification/05-document.md`：照 `docs/verification/04-closed-loop.md` 的体例，含环境、命令、真实输出、未验证项；**分别记录 local 模式与 MQ 模式**
- `docs/known-issues.md` 追加：扫描件、jtokkit 偏差、无 outbox、多实例不支持、按 token 后成本上升
- `docs/design/overview.md` 把模块五打勾
- commit 拆开：`feat(document)` / `test(document)` / `docs(document)`

---

## 5. 完成定义（DoD）

全部满足才算完成：

1. `mvn -Dtest='!*IntegrationTest' test` 全绿，且**模块一到四的 120 个测试一个没改**
2. 集成测试类在本机 Docker 下全绿（含复跑 `TimelineEntryIntegrationTest`，确认没被影响）
3. 设计文档 10.5 的六条 curl 在 `local` 模式通过，输出贴进验证文档
4. RocketMQ 模式手工验收：两条 topic 都能消费、kill 进程后消息重投、停 MQ 时上传返回 503/50302
5. `GET /api/documents/{id}/chunks` 的 `content` 与 `char_start/char_end` 对应（人工抽查 3 条）
6. 验证文档里明确区分「沙箱跑的」「本机 local 模式」「本机 MQ 模式」，未验证的写未验证

---

## 6. 每步做完，按这个格式回报

```
### 第 N 步：<名称>
- 新增/修改文件：<列表>
- 关键实现：<3–5 条，说明为什么这么做>
- 测试：<新增几个、覆盖什么>；执行结果：<命令 + 通过数>
- 偏离设计的地方：<没有就写「无」>
- 遗留问题：<没有就写「无」>
- 下一步是否可以开始：<是/否 + 原因>
```

**不要**在一步里顺手做下一步的事；**不要**把「跑不过」写成「应该能过」。

---

## 7. 遇到这些情况，先停下来问，不要自己决定

1. `cast(#{embedding} as vector)` 在真库上报类型错误
2. Tika 最新 3.x 引入后与 Spring Boot 4 / Java 21 冲突，或打包体积超出预期
3. 现有测试因为你的改动而失败（说明你动了不该动的地方）
4. 设计文档里某条与实际代码矛盾
5. 需要新增设计文档里没有的依赖、表、字段

---

## 8. 明确不要做的事

- 不要接入对话（模块六的事）
- 不要做 rerank、混合检索、父子分块、语义分块
- 不要做 OCR、表格结构化、docx
- 不要给入库做 SSE（轮询就够）
- 不要加 RocketMQ Dashboard（用消费日志和 `mqadmin`）
- 不要加 Grafana / Langfuse / Prometheus
- 不要改 `docs/release/v0.1.0.md`，不要动 tag
- 不要 `git commit --amend`、不要 force push、不要合并到 main
- 不要为了让测试过而放宽断言
- 不要在 `document` 包外面复制一套工具类；`normalize / truncate` 这类工具如果和 `ContextAssembler` 里的重复，先复制（模块五不重构模块四），并在注释里标注「与 ContextAssembler 重复，后续合并」
