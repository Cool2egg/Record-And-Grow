# RecordAndGrow 设计说明

> 本文是项目的设计底稿，随模块推进持续更新。定位、原则、架构、数据模型与模块规划在此沉淀，作为后续每个模块实现时的参照。

## 1. 定位与目标

一个「懂你的个人上下文对话 Agent」：基于个人时间线、文档和对话记忆，回答用户的问题并给出结合个人习惯的建议。

作为简历项目展示给 HR / 面试官，三价值：**可运行、可学习、可展示**。与「牛券」（高并发优惠券系统）互补——牛券证明高并发/分布式/后端工程能力，本项目证明 AI 应用工程能力。

## 2. 产品原则

三条贯穿所有功能设计、数据模型与 prompt 设计的核心原则：

1. **事实 vs 推断分离**：用户主动记录的事件是事实，直接永久保存；AI 从记录/文档提炼、推断出的长期记忆，必须经用户确认后才能保存为可信记忆；AI 不把自己的推测当成用户事实。
2. **记忆需确认**：AI 核心职责是根据对话回答问题，记忆提炼按需触发，非每次对话的强制动作。
3. **三源分别标注**：时间线（用户事实）、文档（用户认可的知识）、联网结果（外部信息，需标注可能过时/不可靠）三个知识源在回答里分别标注。

## 3. 总体架构

分层（业界通用，参考 Ragent）：

- **controller**：HTTP 接口入口
- **ai**：LLM 抽象（自建 ChatClient / EmbeddingClient 接口，HTTP 调 OpenAI 兼容接口）
- **agent**：编排（意图识别与路由 → 并行检索 → 融合重排 → 组装 prompt → 流式生成 → 记忆提炼）
- **retrieval**：检索（向量 / 图谱 / 融合重排）
- **memory**：记忆（会话记忆 + 长期记忆三态）
- **document**：文档（解析 / 分块 / 向量化）
- **timeline**：时间线（用户事实）
- **infra**：横切基础设施（统一响应 / 统一异常 / 参数校验 / 健康检查）
- **config**：Bean 装配

**Agent 核心流转**：

接收提问 → 加载会话记忆 → 意图识别 + 路由 → 并行检索多知识源 → RRF 融合 + Rerank → 分类标注组装 prompt → LLM 流式生成带来源回答 → 按需提炼记忆候选落 PENDING → 用户确认转 FACT。

## 4. 数据模型（基础六表）

| 表 | 职责 | 关键字段 |
|---|---|---|
| timeline_entry | 用户主动记录的事实 | start_time / end_time / content |
| document | 文档元信息 | 标题、类型、来源 |
| document_chunk | 文档分块 | 内容 + embedding（pgvector） |
| memory | 长期记忆 | type: FACT / INFERRED / PENDING + evidence 关联来源 |
| conversation | 会话 | 会话维度 |
| message | 会话消息 | 对话轮次 |

回答的「来源标注」（给人看）与记忆的 evidence（结构化 id 关联）分开存。

## 5. 模块规划

按硬依赖顺序推进，每个模块是一个可独立提交、可验证、可写一篇学习记录的工作单元：

1. 项目骨架（本模块）
2. 时间线：timeline_entry 表 + CRUD + 按日期查询
3. AI 接入：ChatClient / EmbeddingClient + DeepSeek + SSE 流式
4. 最小端到端闭环：时间线记录 → 检索 → LLM 回答 → SSE 输出
5. 文档入库：Tika 解析 + 分块 + 向量化写 pgvector
6. 文档检索接入：时间线 + 文档两路检索（**下一步**）
7. 知识图谱：Neo4j + LightRAG + 实体关系抽取 + 图检索
8. 意图识别 + 多知识库路由
9. RRF 融合 + Rerank
10. 记忆系统：会话记忆 + 摘要 + 记忆三态 + 用户确认流转
11. Agent 编排（收口）：完整闭环
12. ReAct + MCP + Skills
13. 前端：React 聊天页 / 时间线页 / 记忆页 / 文档页

贯穿线：测试（模块 2 起关键路径测试 + Testcontainers）、可观测（Langfuse 模块 3 后接入）、模型容错降级（模块 3 同步）。

## 6. 技术选型

- Java 21 + Spring Boot 4.0 + Maven
- 手搓 LLM 接入（不用 Spring AI / LangChain4j）
- ORM：MyBatis-Plus；存储：PostgreSQL + pgvector + Neo4j + LightRAG
- 文档解析：Apache Tika；流式：SSE；可观测：Langfuse
- 测试：JUnit 5 + Spring Boot Test + Testcontainers
- 明确不做：模型档位 + 首包探测 + 熔断降级（高并发机制，单用户用不上）

## 7. 当前进度

- [x] 模块 1：项目骨架
- [x] 模块 2：时间线
- [x] 模块 3：AI 接入
- [x] 模块 4：最小端到端闭环（非集成测试 120 个；本机 Docker 集成 15/0；3.2–3.6 真实 key/SSE/停库/取消已实测通过）
- [x] 模块 5：文档入库（沙箱：编译 + 224 个非集成测试通过；本机集成/curl/MQ 待跑，见 `docs/verification/05-document.md`）
