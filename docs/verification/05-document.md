# 模块五验证结果（2026-10-20）

## 1. 环境

- 沙箱：Linux（挂载 `D:\RecordAndGrow`），JDK 21.0.12（临时解包到 `/tmp/tools`）、Maven 3.9.9，**无 Docker**。
- 本机：Windows 11，JDK 21.0.12、Maven 3.9.16、Docker、RocketMQ 5.3.2 发行版（见 `docs/dev-environment.md`）。
- 分工：沙箱写代码 + 跑非集成测试；集成测试、curl、MQ 验收在本机跑。

## 2. 编译与非集成测试

```bash
mvn -DskipTests compile
mvn -Dtest='!*IntegrationTest' test
```

结果（沙箱，2026-10-20）：

```text
compile: BUILD SUCCESS
tests:   Tests run: 224, Failures: 0, Errors: 0, Skipped: 2
```

其中模块一到四的 **120 个测试一个没改**，全部通过；新增 104 个（`Skipped: 2` 是
`VectorCastProbeTest`，无 Docker 时自动跳过，**不算通过**，本机必须跑满）。

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `VectorCastProbeTest` | 2 | JDBC String → `cast(? as vector)`、维度不符被拒 |
| `ChunkerTest` | 22 | 归一化、token 上限、重叠按句、code point 硬切、代理对、Markdown 面包屑、小段丢弃、maxChunks、不变式、确定性 |
| `TokenCounterTest` | 4 | 空串、稳定性、中英文计数关系、实现标识 |
| `DocumentParserTest` | 9 | txt/md/pdf、损坏 PDF、空文件、伪装扩展名、过短文本、超 max-chars、415 |
| `DocumentStorageTest` | 5 | sha256 文件名、重复不覆盖、删除、缺文件、越界保护 |
| `DocumentIngestServiceTest` | 24 | 状态机、幂等、断点续跑、去重、未配置 key、并发守卫、截断 error_message |
| `DocumentEmbeddingWireMockTest` | 4 | 25 块 → 10/10/5、乱序对位、429 重试、维度不符不写 |
| `VectorLiteralsTest` | 3 | Locale 无关、空向量、非有限值 |
| `DocumentControllerTest` | 16 | 202/200/409/413/415/404/503、响应裁剪、发消息时机、缺 part 400 |
| `DocumentUploadExceptionHandlerTest` | 3 | 413 / 400 / 400 |
| `DocumentReconcilerTest` | 5 | PENDING→parse、EMBEDDING→embed、封顶判失败、单条失败不影响其它 |
| `AbstractDocumentConsumerTest` | 7 | 首投/第 3 次重投、第 4 次判失败、坏消息确认、markFailed 失败仍确认 |

## 3. 沙箱跑不了的部分

`VectorCastProbeTest` 在无 Docker 时自动跳过；`DocumentSchemaIntegrationTest`、`DocumentIngestIntegrationTest` 会直接失败。沙箱里后者报：

```text
Could not find a valid Docker environment.
```

**这两项不算通过**，命令与期望见第 4 节。

## 4. 待本机执行（必须）

```powershell
# 4.1 pgvector cast 探针（模块唯一 [推断] 点，最先跑）
docker compose up -d postgres
mvn -Dtest=VectorCastProbeTest test
# 期望：Tests run: 2, Failures: 0

# 4.2 库结构
mvn -Dtest=DocumentSchemaIntegrationTest test
# 期望：Tests run: 6, Failures: 0
# 其中 castFromJdbcStringShouldInsertVector 是「JDBC 传字符串 + cast(? as vector)」

# 4.3 入库全链路（真实 pgvector + WireMock，不连真实 embedding）
mvn -Dtest=DocumentIngestIntegrationTest test
# 期望：Tests run: 4, Failures: 0

# 4.4 模块一到四回归（确认没被影响）
mvn -Dtest='*IntegrationTest' test
# 期望：TimelineEntryIntegrationTest 15 个 + 新增 3 个集成类全绿
```

完整可复制清单（按「最早暴露问题优先」排序）见 `docs/verification/05-local-runbook.md`。

如果 4.1 的 `castFromJdbcStringShouldInsertVector` 报 42846（无法从 varchar 转 vector），**停下来**，按设计稿的退化方案处理（驱动 scope + `PGobject`），不要自行改方案。

## 5. local 模式 curl 六项（待本机）

启动（不配置 key 时应返回 503，而不是启动失败）：

```powershell
$env:DASHSCOPE_API_KEY = "<真实 key>"
$env:DOCUMENT_QUEUE_MODE = "local"
mvn spring-boot:run
```

```bash
# 1) 新建 → 202
curl -i -F "file=@docs/design/overview.md" http://localhost:8080/api/documents

# 2) 同一文件再传 → 200，deduplicated=true
curl -i -F "file=@docs/design/overview.md" http://localhost:8080/api/documents

# 3) 详情 → 200，响应里没有 storagePath / normalizedText
curl -s http://localhost:8080/api/documents/1

# 4) 分块 → 200，响应里没有 embedding；content 与 char_start/char_end 能对上
curl -s "http://localhost:8080/api/documents/1/chunks?size=100"

# 5) 重建 → 202，轮询到 READY
curl -i -X POST http://localhost:8080/api/documents/1/reindex
curl -s http://localhost:8080/api/documents/1

# 6) 删除 → 200，再查 → 404
curl -i -X DELETE http://localhost:8080/api/documents/1
curl -i http://localhost:8080/api/documents/1
```

期望补充：
- 上传后立即返回 PENDING；等几秒再查变 READY。
- 处理中（PARSING/EMBEDDING）调 reindex 或 delete → 409/40900。
- 传 `.docx` → 415/41500；传 >12MB → 413/41300；不设 key → 503/50301。

## 6. RocketMQ 模式手工验收（待本机）

本机已有 RocketMQ 5.3.2，直接用它（不必起 compose 的 rocketmq 容器）：

```powershell
cd 'D:\rocketmq-5.3.2\rocketmq-all-5.3.2-bin-release'
.\bin\mqnamesrv.cmd                                  # 窗口 1
.\bin\mqbroker.cmd -n 127.0.0.1:9876 autoCreateTopicEnable=true   # 窗口 2
```

应用侧：

```powershell
$env:DOCUMENT_QUEUE_MODE = "rocketmq"
$env:ROCKETMQ_NAMESRV = "127.0.0.1:9876"
mvn spring-boot:run
```

要验证的四件事：

1. **两条 topic 都有消费日志**：上传后日志依次出现 `parse` 与 `embed` 两阶段的消费记录，最终 READY。
2. **重投次数起点**：故意让一份坏 PDF 失败，观察日志里的 `reconsumeTimes` 是否为 `0,1,2,3` 四次；若是 `1..4`，`AbstractDocumentConsumer` 的判定要从 `>=` 改成 `>`（文档里已注明以实测为准）。
3. **kill 进程后消息重投**：上传后立刻结束应用，重启后消息应被重新消费并跑到 READY。
4. **停 MQ 时上传**：broker 停掉后再上传 → 503/50302，记录留在库里；恢复 MQ 并重启应用后，对账（5 分钟一次）应补发并跑到 READY。

## 7. 未验证项（明确记录）

- `cast(? as vector)` 只在本机 Docker 下才能验，沙箱未验。
- Tika **解析超时路径**没有自动化用例（需要构造卡死的解析器），代码有超时与取消，但未实测。
- RocketMQ **首次投递的 `getReconsumeTimes()` 起点**没有在本机实测，代码按「0 开始、第 4 次为 3」写。
- 对账任务真实补发未实测（单测只验决策逻辑）。
- MQ 重投与对账叠加时的并发行为未压测；单实例前提见 known-issues。

## 8. 实现上的两处技术决定（与设计稿的差异）

1. **Tika 用 3.3.3，不是 4.1.0**。4.1.0 于 2026-10-13 发布，是刚出的新大版本；3.3.3 是 3.x 最新稳定版，风险更低。另外 3.x 的 `tika-parsers-standard-package` **不再传递 `tika-core`**，必须显式声明，否则编译直接找不到类。
2. **对账新增 `last_progress_at` 列**（设计稿第 2 节 DDL 没有）。这是对齐结论 #22 的要求：用 `updated_at` 会把正在跑的大文档误判成卡住。索引相应改为 `(status, last_progress_at)`。
3. `document` 的 DDL 在 `document` 表上比设计稿多一列 `last_progress_at`，其余照抄。

## 9. 依赖核对

- `rocketmq-client 5.5.1`（Maven Central 最新）。Boot 4.0.8 的 BOM 没有把 `netty-all` 顶到 4.2：`mvn dependency:tree -Dincludes=io.netty` 实测全部解析为 **4.1.130.Final**（RocketMQ 自带 netty-bom 生效），因此**没有额外 pin**。
- 依赖总数 222 个（Tika 标准包 + RocketMQ 客户端贡献了大部分），见 `mvn dependency:list`。

## 10. 相关文档

- 设计稿：`docs/design/module-05-document-ingest.md`
- 已知问题：`docs/known-issues.md`
- 上一模块验收：`docs/verification/04-closed-loop.md`
