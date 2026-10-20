# 模块五本机执行清单（2026-10-20）

> **按顺序跑，第 1 条不过就停，后面的不要跑**——越靠前的问题影响面越大。
> 全部在仓库根目录 `D:\RecordAndGrow` 执行，PowerShell 会话；先确认 `mvn -v` 显示 JDK 21。

## 0. 前置

```powershell
mvn -v
```
通过标准：`Apache Maven 3.9.16` + `Java version: 21.0.12.1`。
失败先看：`JAVA_HOME` 是否指向 `C:\Users\cool2egg\.jdks\temurin-21.0.12.1`（见 `docs/dev-environment.md`）。

```powershell
docker info
```
通过标准：能打印 Server 版本。
失败先看：Docker Desktop 是否启动。

## 1. 起数据库

```powershell
docker compose up -d postgres
docker compose ps
```
通过标准：`recordandgrow-postgres` 为 `healthy`。
失败先看：5432 端口是否被占用、旧容器是否已存在（`docker compose down` 后重来）。

## 2. pgvector 字符串绑定探针（最先跑，模块唯一的推断点）

```powershell
mvn -Dtest=VectorCastProbeTest test
```
通过标准：`Tests run: 2, Failures: 0`。
失败先看：报 42846 / `cannot cast type character varying to vector` 就是绑定方式不成立，**停下来**，按设计稿退化方案（改 `postgresql` scope + `PGobject`）再继续，不要硬改测试。

## 3. 库结构

```powershell
mvn -Dtest=DocumentSchemaIntegrationTest test
```
通过标准：`Tests run: 6, Failures: 0`（扩展、`vector_dims=1024`、HNSW、部分唯一索引、软删后可重传、级联删除）。
失败先看：`uk_document_sha256_active` 的 `indexdef` 是否含 `deleted = false`；HNSW 是否支持（pgvector 版本）。

## 4. 入库全链路（真实 pgvector + WireMock，不连真实 key）

```powershell
mvn -Dtest=DocumentIngestIntegrationTest test
```
通过标准：`Tests run: 4, Failures: 0`，且每例都能跑到 READY。
失败先看：`status=FAILED` 时读 `document.error_message`；WireMock 端口被占会直接连不上。

## 5. 集成回归（含模块二）

```powershell
mvn -Dtest='*IntegrationTest' test
```
通过标准：`TimelineEntryIntegrationTest` 15 个 + 本次新增 3 个集成类全绿。
失败先看：模块二的用例失败说明动了不该动的地方（V1 迁移、时间线代码）。

## 6. 全量非集成测试

```powershell
mvn -Dtest='!*IntegrationTest' test
```
通过标准：`Tests run: 224, Failures: 0`（模块一到四 120 个 + 模块五新增 104 个）。
失败先看：先确认失败的是新增用例还是既有用例；既有用例失败一律视为回归。

## 7. RocketMQ 冒烟（第 6 步）

```powershell
cd 'D:\rocketmq-5.3.2\rocketmq-all-5.3.2-bin-release'
.\bin\mqnamesrv.cmd
# 新窗口
.\bin\mqbroker.cmd -n 127.0.0.1:9876 autoCreateTopicEnable=true
```

```powershell
cd D:\RecordAndGrow
$env:DOCUMENT_QUEUE_MODE = "rocketmq"
$env:ROCKETMQ_NAMESRV = "127.0.0.1:9876"
$env:DASHSCOPE_API_KEY = "<真实 key>"
mvn spring-boot:run
```

```powershell
curl -i -F "file=@docs/design/overview.md" http://localhost:8080/api/documents
```
通过标准：先 202，几秒后 `GET /api/documents/{id}` 变 READY；日志里能看到 `parse-consumer` 与 `embed-consumer` 两个消费组的记录。
失败先看：`RocketMQ producer 启动失败` → namesrv 地址；`brokerIP1` 是否 127.0.0.1。

```powershell
# kill 应用后重启：消息应被重新消费，最终 READY
# 停 broker 后上传：应返回 503/50302，记录留在 PENDING
```
通过标准：重投能跑到 READY；停 MQ 时 `HTTP 503` + `code=50302`；恢复并重启后对账补发。
失败先看：日志里的 `reconsumeTimes` 是 0,1,2,3 还是 1..4；后者要把判定从 `>=` 改成 `>`。

## 8. curl 六项（local 模式）

先停掉 rocketmq 应用，改回：

```powershell
$env:DOCUMENT_QUEUE_MODE = "local"
mvn spring-boot:run
```

```powershell
curl -i -F "file=@docs/design/overview.md" http://localhost:8080/api/documents          # 202
curl -i -F "file=@docs/design/overview.md" http://localhost:8080/api/documents          # 200 deduplicated=true
curl -s http://localhost:8080/api/documents/1                                            # 200 无 storagePath/normalizedText
curl -s "http://localhost:8080/api/documents/1/chunks?size=100"                          # 200 无 embedding
curl -i -X POST http://localhost:8080/api/documents/1/reindex                            # 202
curl -i -X DELETE http://localhost:8080/api/documents/1                                  # 200，再 GET → 404
```
通过标准：六个状态码与上面注释一致；chunks 里 `content` 与 `char_start/char_end` 人工抽查 3 条能对上 `normalized_text`。
失败先看：409 说明文档还在处理中，等一下再试；415 说明扩展名不在白名单。
