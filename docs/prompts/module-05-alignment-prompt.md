# 模块五：开发前对齐（第一轮，只读，不写代码）

> 用途：动手前确认「理解一致、方案落位、风险已知」。
> **本轮禁止写代码、禁止改文件、禁止建分支、禁止装依赖。** 只允许读文件、`git status`、`mvn -v`。

---

你现在不是来实现的，是来对齐的。目标：让双方在动手前对四件事达成一致——**做什么、怎么做、哪里会错、我需要你定什么**。

本轮方案已定：Tika 解析、**RocketMQ 两阶段异步**、**按 token 切块**。不要重新讨论这三点，讨论怎么把它们做对。

## 一、先读

1. `docs/design/overview.md`
2. `docs/design/module-05-document-ingest.md`（**权威方案 v2**，注意第 4 节 RocketMQ、第 5 节 token 切块、第 14 节）
3. `docs/known-issues.md`
4. `docs/verification/04-closed-loop.md`（上一模块的验收口径）
5. 代码：
   - `src/main/resources/db/migration/V1__create_timeline_entry.sql`
   - `src/main/java/com/recordandgrow/timeline/`（四个文件）
   - `src/main/java/com/recordandgrow/ai/EmbeddingClient.java`、`ai/qwen/QwenEmbeddingClient.java`、`ai/config/AiProperties.java`
   - `src/main/java/com/recordandgrow/infra/exception/*.java`、`infra/response/*.java`
   - `src/main/java/com/recordandgrow/config/{ChatConfig,ChatProperties,MybatisPlusMetaObjectHandler}.java`
   - `src/test/java/com/recordandgrow/timeline/TimelineEntryIntegrationTest.java`
   - `pom.xml`、`src/main/resources/application.yml`、`docker-compose.yml`

## 二、回报格式（≤150 行，不要复述文档）

### A. 事实核对

逐条确认，**不一致就指出实际是什么**：

1. `EmbeddingClient` 只有 `embed()` / `dimensions()`，没有 `isConfigured()`
2. `QwenEmbeddingClient` 内部按 10 条分批，业务层不需要再分批
3. `TimelineEntryMapper` 用 `@Mapper` + `BaseMapper`，无 XML
4. `ChatProperties` 通过 `@EnableConfigurationProperties` 挂在 `ChatConfig`
5. `MybatisPlusMetaObjectHandler` 自动填 `createdAt/updatedAt`
6. `ErrorCode` 现有 7 个值，`BusinessException(ErrorCode, message)`
7. `TimelineEntryIntegrationTest` 用 `pgvector/pgvector:pg16` + 程序化 Flyway + `@Transactional`
8. `postgresql` 依赖是 `runtime` scope
9. `docker-compose.yml` 目前只有 postgres 和 flyway，没有 MQ
10. `application.yml` 里 embedding 的 `batch-size=10`、`dimensions=1024`

### B. 范围复述（各 ≤5 条）

- 本模块要做的
- 明确不做的（含：OCR、docx、顺序消息、事务消息、outbox、DLQ 自动补偿）
- 不许碰的

### C. 方案落位

完整文件路径清单（含包名），每个一句话职责。特别说明：

- `DocumentIngestQueue` 的接口、两个实现的类名与触发条件
- `DocumentChunkWriter`（独立 Bean）为什么不能被 `ingest()` 自调用
- RocketMQ 的 producer / consumer 在哪个类启停，用什么生命周期接口
- 状态机切换点分别在哪个方法

### D. 必须回答（要具体做法，不要表态）

1. **pgvector cast**：`cast(#{embedding} as vector)` 传字符串，你打算用什么**可执行**的方式在写业务前验证？（一条 SQL 或一个几十行的测试）
2. **RocketMQ 客户端**：你查到的 `rocketmq-client` 版本是多少？和 Spring Boot 4 / JDK 21 有已知冲突吗？如果没有依据，明确说"不确定"，不要猜
3. **幂等**：同一条 parse 消息被投递两次，你的代码路径是什么？同一条 embed 消息被投递两次呢？用伪代码说明，不要用"我会保证幂等"
4. **重试边界**：`maxReconsumeTimes=3` 时，第 4 次投递的判定条件写在监听器哪里？`getReconsumeTimes()` 的取值从 0 还是 1 开始，你怎么确认？
5. **对账**：`updated_at < now() - 10min` 这条查询，和「正在正常处理的长文档」怎么区分？会不会把一份还在跑的大 PDF 误判成卡住？
6. **token 计数**：jtokkit 对中文的偏差你打算怎么量化？给出一个具体测法（用一段中文和一段英文，比较字符数与 token 数的比值）
7. **重叠与偏移**：相邻块 `[start,end)` 相交时，`content == normalized[start,end)` 这条不变式怎么保证？重叠部分在下一块里算不算进 `char_start`？
8. **多实例**：为什么现在不能扩到两个消费者实例？给出会出问题的具体场景
9. **测试**：单测命令、集成测试命令分别是什么？集成测试要 Docker 吗？要 RocketMQ 吗？环境事实见 `docs/dev-environment.md`（`mvn` 不在 PATH，JDK 21 在 `~\.jdks\temurin-21.0.12.1`）

### E. 风险

你认为最可能出错的三处，以及提前验证的方式（不要抄设计稿的风险表）。

### F. 需要我拍板

只列真正会改实现的，按「A / B + 你推荐哪个」写，最多 3 个。

### G. 一句话

> 这份设计稿里最值得怀疑的一条是 ______，因为 ______。

## 三、红线

- 不写代码、不建文件、不切分支、不装依赖
- 不"顺手"做第 1 步
- 不笼统回答（「我会注意」「一般没问题」都不算）
- 查不到就说查不到
