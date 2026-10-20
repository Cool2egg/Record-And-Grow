# 模块五：一次性完成 1–7 步

继续模块五。这次连续做第 1 到第 7 步，中途不再等我确认。规则如下，任何一条不满足就停下报告，不要硬推。

## 一、硬性节奏（每步都要过闸）

1. 每步结束必须过两道闸：
   - `mvn -B test-compile` 通过
   - `mvn -B -Dtest='!*IntegrationTest' test` 全绿（模块一到四的 120 个测试一个不许改）
2. **过闸才允许开下一步**；不过闸就停，报告：卡在哪一步、完整报错、你的判断、打算怎么改。不要为了让闸通过而删测试或放宽断言。
3. 每步单独 commit（`feat(document)` / `test(document)` / `docs(document)` 分开），不要攒到最后一次性提交。

## 二、环境边界（决定你怎么写文档）

- 你在 Linux 沙箱，**没有 Docker**：Testcontainers 集成测试、RocketMQ、curl 只能写、只能保证编译，**不能运行**。
- 因此：任何回报和文档里**不许写「集成测试通过」**，统一写「待本机验证」。
- 探针测试（pgvector `cast`）也一样：写成 JUnit 测试交给本机跑，不许写「推测可行」。
- 沙箱能验的只有：编译、非集成测试、MockMvc。

## 三、遇到走不通的设计点

不要停下来等我。按设计稿的退化方案处理，并在两处记录：

1. 代码注释里写清为什么改；
2. 最终报告里单列一节「偏离设计稿的地方」，每条给出原因和影响。

允许自行退化的问题只有这几类：pgvector 字符串绑定、Tika 依赖冲突、MyBatis 异常翻译（`DuplicateKeyException` 还是 `PersistenceException`）、`getReconsumeTimes()` 起点。**其余一律按设计稿执行**，不许自己加表、加依赖、加接口。

## 四、交付物

### 1. 代码（第 1–6 步）

按 `docs/design/module-05-document-ingest.md` v2.1 执行，注意第 13 节已确认的四条决策和第 14、15 节的对齐结论。

### 2. `docs/verification/05-document.md`

照 `docs/verification/04-closed-loop.md` 的体例，但必须分两栏：

- **沙箱已验证**：编译、单测、MockMvc，写清命令与通过数；
- **待本机验证**：每条一行，命令 + 期望输出，不许含糊。

### 3. `docs/verification/05-local-runbook.md`

给我回来一次性跑的执行清单，**按「最早暴露问题优先」排序**，一条命令一行，可直接复制。至少包含：

1. `docker compose up -d postgres`
2. pgvector 探针：`mvn -Dtest=VectorCastProbeTest test`
3. `mvn -Dtest=DocumentSchemaIntegrationTest test`
4. `mvn -Dtest=DocumentIngestIntegrationTest test`
5. 回归：`mvn -Dtest='*IntegrationTest' test`（含模块二的 `TimelineEntryIntegrationTest`）
6. 全量：`mvn -Dtest='!*IntegrationTest' test`
7. 本机 RocketMQ 冒烟（第 6 步做完才需要）：起 namesrv/broker、`queue.mode=rocketmq`、上传、观察两条 topic、kill 进程看重投、停 MQ 看 503/50302
8. curl 六项验收（照设计稿 10.5）
9. 每一条都要写「通过标准」和「失败时先看什么」

清单开头写一句：**按顺序跑，第 1 条不过就停，后面的不要跑**——因为越靠前的问题影响面越大。

### 4. 文档收尾

`docs/known-issues.md` 追加：扫描件无文本、jtokkit 与 Qwen 分词偏差、无 outbox、单实例限制、按 token 后 embedding 成本上升、本模块未做的部分。
`docs/design/overview.md` 把模块五打勾，模块六设为下一步。

## 五、每步回报格式

```
### 第 N 步：<名称>
- 新增/修改文件：<列表>
- 关键实现：<3–5 条，说明为什么>
- 闸门结果：test-compile <通过/失败>；非集成测试 <通过数>
- 偏离设计：<没有就写「无」>
- 待本机验证：<本步新增的条目，没有就写「无」>
- 下一步是否可以开始：<是/否 + 原因>
```

七步全部走完，最后再给一份**总报告**：

- 七步的闸门结果一览；
- 新增测试数与总测试数；
- 偏离设计稿的完整清单；
- `05-local-runbook.md` 的条目数，以及你最没把握的是哪一条。

## 六、红线（沿用原提示词第 8 节）

- 不改 `ChatService` / `ContextAssembler` / `ChatController` / `SseEvents`
- 不改 V1 迁移，不改 `postgresql` 依赖 scope
- 不做 OCR / docx / 表格结构化 / 父子分块 / rerank / SSE 入库 / Dashboard
- 不加新依赖（只允许 Tika、rocketmq-client、jtokkit）
- 不 `commit --amend`、不 force push、不合并 main
- 不声称未运行的测试通过
