# RecordAndGrow 与 Ragent 对比：现状、差距与下一步

> 日期：2026-10-21。对照对象：`nageoffer/ragent` `main@ff93871`（详见 [Ragent 源码实现研究](ragent-implementation.md)）。
> 口径：只比较当前工作区代码与 `docs/design/overview.md` 的规划，不把设计稿当已实现。

## 0. 结论

1. **RecordAndGrow 已完成 6/13，工程底座是强项，产品形态仍是「单轮 + 两路串行 + 无前端」**。它已经是一个能跑通的 RAG 闭环，但还不是一个「能聊、能看、能证明效果」的产品。
2. **Ragent 领先的是「检索漏斗 + Agent 运行时 + 平台化运维」，不是模型或 Prompt**。它的 RAG 主链路确实更完整，但 32 张表、7 个模块、双运行时是平台税，不适合直接搬。
3. **下一步不建议先做知识图谱**。按「补短板」而不是「加功能」排序：先合并发版、补多轮/记忆、做 RRF/Rerank、补最小前端与评测；KG 和 ReAct/MCP 后置。

## 1. 现状快照

| 项 | 当前值 |
|---|---|
| 主代码 | 113 个 Java 文件 / 7,227 行 |
| 测试 | 41 个测试文件 / 5,822 行；文档记录 279 个非集成 + 30 个集成用例全绿 |
| 数据库 | 3 张表（`timeline_entry`、`document`、`document_chunk`），2 个 Flyway 迁移 |
| 接口 | 5 个 Controller：chat / timeline / document / health / AI 诊断 |
| 已完成 | 模块 1–6：骨架、时间线、AI 接入、闭环、文档入库、文档检索 |
| 未完成 | 模块 7–13：图谱、意图、RRF/Rerank、记忆、Agent 收口、ReAct/MCP、前端 |
| 检索现状 | 时间线 SQL 关键词 + 文档 pgvector 向量，**两路串行**，无意图、无融合、无 rerank |
| 会话现状 | **单轮无状态**，不接受 history；「那第二天呢」会回落默认时间窗 |
| 展示/评测 | 无前端、无 golden set、无召回率/引用正确率指标 |

**交付风险**：当前在 `module/doc-retrieval` 分支，领先 `main` **37 个提交**；`main` 还停在模块 2（2026-10-17），模块 3–6 都还没合并。`v0.1.0` tag 打在 `module/closed-loop`，不是 `main`。这是当前最该先处理的事，比继续开模块 7 更紧急。

## 2. 能力对比

| 维度 | RecordAndGrow | Ragent | 判断 |
|---|---|---|---|
| 定位 | 单用户「个人上下文」Agent | 平台型 Agentic RAG | 不追平台广度 |
| 规模 | 7.2k 主代码 / 3 表 | 10.5 万行 / 32 表 / 7 模块 | 不照搬规模 |
| 检索 | 时间线关键词 + 文档向量，串行 | Scope + 4 通道 + RRF + Rerank + 证据闸门 | 抄「漏斗」，不抄四通道 |
| 意图 | 无，固定两路都查 | 树形意图 + 一次打分 + 歧义澄清 | 模块 8 做最小版 |
| 记忆 | 无，单轮 | 摘要 + Agent 三层记忆（自动写入） | 模块 10 抄结构、改确认 |
| 图谱 | 计划 Neo4j + LightRAG | LightRAG 作为一条检索通道 | 降级/延后 |
| Agent | 固定路径 | AgentScope ReAct + MCP + Skills | 后置，不搬全家桶 |
| 工程 | 失败语义、SSE 取消/心跳、测试密度强 | 配置校验、降级、Trace、模型路由强 | 各有优势 |
| 展示/评测 | 无 | 完整控制台 + Langfuse | 最大展示短板 |

## 3. Ragent 真正值得抄的 6 个机制

1. **检索漏斗**：`recall 20 / candidate 40 / topK 10`，`recall ≥ topK`、`candidate ≥ topK` 启动校验；RRF `k=20`（候选池只有 20–40 条，不用经典 60）；Rerank 后按整批最高分过 `0.2` 证据闸门。这是模块 9 的直接蓝图。
2. **意图树**：一张 `intent_node` 表 + Redis 缓存；一次 LLM 给所有叶子打分；阈值 0.35、最多 3 个；歧义只在「单子问题 + 路径重名」时澄清。RecordAndGrow 可以先做 4–6 个叶子（TIMELINE / DOCUMENT / MEMORY / SYSTEM）。
3. **结构化分块**：先解析成 `Block` 再切，按标题分节、按块类型分块；展示正文 `content` 与向量文本 `embedding_text` 分列。RecordAndGrow 现在是纯 token 切分，后续换解析器/换嵌入模型会受益。
4. **摘要水位线**：最近 8 轮 + 第 9 轮起摘要 400 字；用 `last_message_id` 做增量水位，摘要覆盖半个窗口，滑出后才重算；异步 + 分布式锁。模块 10 可以直接借。
5. **确认式工具调用**：前端只传 approve/deny，工具参数从服务端 Agent 状态回取；写操作先落 `AWAITING_CONFIRM`，恢复时用 `ConfirmResult` 续跑。这个模式可以同时用于「记忆候选确认」。
6. **首包探测 + 配置 fail-fast**：首包成功前缓冲、失败才换模型，用户看不到半截脏 token；检索预算/阈值/档位在启动期校验，矛盾直接失败。RecordAndGrow 的失败语义已经不错，但「配置启动校验」还可以补。

## 4. 不要照搬的 5 个东西

| 不抄 | 原因 |
|---|---|
| v1/v2 双运行时、双会话/双消息表 | 历史分裂、功能重复；RecordAndGrow 一套 `conversation + message + mode` 足够 |
| 32 张表 / 7 模块 / 9 线程池 | 平台税；单用户项目 12 张表以内就能闭环 |
| 公平排队 + 分布式信号量 | 单用户没有并发争抢；现有 SSE + 单机队列足够 |
| 自动长期记忆（自动 supersede、写入无需确认） | 与「事实 vs 推断、记忆需确认」的产品原则冲突；必须 PENDING → 用户确认 → FACT |
| 可编排 Pipeline / Langfuse / MCP server 全家桶 | 知识库上传路径的 Pipeline 在 Ragent 代码里是禁用状态（会抛「重构中」）；Trace 先把 requestId + 结构化日志做好 |

## 5. 真正的三个差距

不是「Ragent 有 32 张表，你只有 3 张」，而是：

1. **不能聊**：单轮无状态是体验致命伤，也是「个人上下文 Agent」最不该缺的能力。多轮 + 最小记忆闭环的展示价值，高于知识图谱。
2. **检索质量不可证**：文档检索现在只有向量 + 阈值 + 每文档 2 块，没有关键词兜底、没有 RRF、没有 rerank、没有评测集。面试官问「召回率多少」时，只能回答「能跑」。
3. **不可展示**：没有前端，所有能力只能 curl；没有 golden set，所有效果只能口头描述。这两项是「简历项目」的入场券，不是加分项。

知识图谱、ReAct/MCP 是加分项；在 1–3 没补齐前做它们，投入产出比很低。Ragent 的图谱也只是 LightRAG 的一条通道，不是它的核心壁垒。

## 6. 建议路线

### P0：先交付，再开新模块

1. 把 `module/ai`、`module/closed-loop`、`module/document-ingest`、`module/doc-retrieval` 按顺序合回 `main`，打 `v0.2.0`。37 个提交堆在一条分支上，越往后合并成本越高。
2. 最小前端：聊天页（输入框 + SSE + 引用卡片）+ 时间线列表页。Ragent 的控制台不用抄，抄交互即可。
3. 15–30 条 golden set：时间线命中、文档命中、混合命中、无命中、降级五类；先人工算召回率/引用正确率/拒答率。

### P1：补产品短板

4. **模块 10 前半**：`conversation` / `message` + 最近 N 轮 + 简单指代改写；先不做摘要，先把「那第二天呢」救回来。
5. **模块 10 后半**：`memory` 表 + PENDING 确认接口 + 注入 Prompt；借 Ragent 的摘要边界和抽取台账，但不要自动 supersede。
6. **模块 9**：把现有 `TimelineRetriever` / `DocumentRetriever` 包成两个 `SearchChannel`，做 Dedup → RRF(k=20) → Rerank → EvidenceGate；预算 `20/40/10`，先两路，不加图谱。

### P2：加分项

7. **模块 8**：意图树 + 一次打分 + 歧义澄清；叶子 4–6 个，不要多知识库绑定。
8. **模块 7**：图谱只作为第三条 `SearchChannel`，LightRAG 负责图，Java 只消费证据；权重先 0.5。
9. **模块 12**：先手写最小 ReAct 循环或做 AgentScope 兼容性 spike；确认式工具调用可以复用模块 10 的确认卡。

## 7. 一句话

**Ragent 是参考实现，不是目标架构。** RecordAndGrow 的胜负手是「个人上下文 + 可确认的记忆 + 能展示的闭环」，不是把 Ragent 的功能表抄完。先合并、先多轮、先融合重排、先前端和评测；图谱和 ReAct 放到后面。
