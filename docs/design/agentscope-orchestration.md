# AgentScope 编排演进方案（规划稿）

> **状态说明**：本文是最初的演进规划，保留用于对比。实际落地选了更稳的**两段式**（检索 Agent + 回答 Agent），
> 没有上多 Agent pipeline / handoff；以 `docs/design/module-11-agentscope.md` 为准。
>
> 面向模块 7–11 的收口设计：把现在手写的线性编排（`ChatService`）演进为 AgentScope Java 驱动的 Agent 编排。
> 最终形态一句话：**一个检索 Agent + 一组领域工具 + 一个无工具的回答 Agent**；HTTP/SSE 契约、领域服务、数据模型都不变。

## 1. 现状与差距

现状（模块 4–6）的编排是写死的六步：

```
预检 → 时间窗/关键词 → 路由 → 并行检索(时间线+文档) → RRF/Rerank → 组装 Prompt → 生成
```

问题不在能力，在**扩展方式**：加一路知识源（图谱/联网）、加一次推理（先澄清再检索）、加一步后处理（记忆提炼），都要改 `ChatService.prepare` 和 `ContextAssembler`，分支越堆越多。

AgentScope 换掉的是「谁来决定下一步」：从 Java 里的固定顺序，变成模型在工具清单上自主决策；领域能力仍由现有服务提供。

## 2. 选型：AgentScope Java，同进程嵌入

| 方案 | 结论 | 理由 |
|---|---|---|
| **AgentScope Java 嵌入主服务** | **采用** | 单进程、单镜像，保住 Java 21 + Spring Boot 4 的部署与调试方式；框架自带 ReAct、Toolkit、Memory、Pipeline、Handoff、MCP、会话状态 |
| AgentScope Python sidecar | 暂不采用 | 多一个运行时与一整套进程/健康检查/链路追踪，收益主要是 Studio 与沙箱；等需要可视化调试或代码沙箱时再拆 |
| 继续手写编排 | 不采用 | 多知识源、多步推理会把 `ChatService` 变成 if-else 沼泽 |

关键约束：**模型层复用现有 `ChatClient`**。写一个 AgentScope `Model` 适配器包住 `FailoverChatClient`，DeepSeek/Qwen/重试/降级/`AiCallObserver` 全部保留，不重复造轮子。

## 3. 目标架构

```
                    ┌──────────────────────────────┐
 POST /api/chat/ask │  ChatController（契约不变）    │
 ──────────────────▶└──────────────┬───────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │ AgentOrchestrator             │
                    │  = AgentScope ReActAgent      │
                    │  + Toolkit + Memory + State   │
                    └───┬──────────┬───────────┬────┘
             tool calls │          │           │ 事件流(thinking/tool/answer)
                        ▼          ▼           ▼
              ┌──────────────┐ ┌─────────┐ ┌──────────────────┐
              │ 检索工具      │ │ 记忆工具 │ │ AgentEventToSse  │
              │ TimelineTool │ │ Memory  │ │ 桥 → 现有 SSE 事件 │
              │ DocumentTool │ │ Tool    │ │ start/sources/    │
              └──────┬───────┘ └────┬────┘ │ delta/done/error  │
                     ▼              ▼      └──────────────────┘
        RetrievalEngine / RRF /   memory 表（三态）
        EvidenceGate / Rerank     PENDING→确认→FACT
```

- **控制面**：AgentScope 负责决策、工具调用、多轮、会话状态、多 Agent 组合。
- **数据面**：现有 `retrieval` / `document` / `timeline` / `memory` 包不动，只加 Tool 包装层。
- **出入口**：`ChatController` 与 SSE 事件格式不变，前端零改动。

## 4. 组件映射

| 现有 | 演进后 | 说明 |
|---|---|---|
| `ChatService.prepare` 固定流水线 | `AgentOrchestrator` + ReActAgent | 顺序由模型决定，Java 只保留预检与参数钳制 |
| `QueryRouter` | `RouterAgent` 或工具内规则 | 先保留规则路由作为「快速通道」，模型路由作为 fallback/升级 |
| `RetrievalEngine` / `DocumentRetriever` / `RrfFusion` | `@Tool search_timeline` / `@Tool search_documents` | 工具内部仍是原有并行、融合、阈值、降级逻辑 |
| `ContextAssembler` | Prompt 模板 + `sources` 结构化结果 | 模板固定，保证 `[Tn]/[Dn]` 标注与三源分离 |
| `ChatSseService` | 保留，前接 `AgentEventToSseBridge` | 把 Agent 的增量文本转 `delta`，工具调用摘要转可选 `step` 事件 |
| `conversation/message` 表 | AgentScope Session 持久化 | 短期记忆放 AgentScope Memory，落库仍走现有表 |
| 记忆三态 | `MemoryTool` + 用户确认接口 | Agent 只能写 PENDING，确认仍由用户触发 |
| `FailoverChatClient` | `ChatClientModelAdapter` | 适配成 AgentScope Model，复用重试与降级 |

## 5. 工具清单（第一版只做前四个）

| 工具 | 背后服务 | 返回 | 备注 |
|---|---|---|---|
| `search_timeline(query, window)` | `TimelineRetriever` | 命中条目 + 时间 | 窗口解析沿用 `TimeWindowParser` |
| `search_documents(query, topK)` | `DocumentRetriever`（含 RRF+Rerank+EvidenceGate） | 文档块 + 分数 + 降级标记 | 降级返回结构化空结果，不抛异常 |
| `get_timeline_by_date(date)` | `TimelineEntryService` | 当天全部记录 | 回答「某天做了什么」 |
| `propose_memory(type, content, evidence)` | `memory` 表 | PENDING 记录 id | **只写 PENDING**，符合「记忆需确认」 |
| `search_memory(query)` | `memory` 表 | 已确认的 FACT/INFERRED | 模块 10 再加 |
| `web_search(query)` | 外部搜索 | 结果 + 时效提示 | 标为外部源，提示可能过时 |
| `search_graph(entity)` | Neo4j / LightRAG | 实体关系 | 模块 7 再加 |

## 6. 编排形态（三步走，每步可独立演示）

**A. 单 Agent（先落地）**
一个 `ReActAgent` 挂 4 个工具，system prompt 固定：三源分别标注、不知道就说不知道、先检索再回答。`maxIters=4`。这一步就能覆盖模块 11 的「完整闭环」。

**B. 多 Agent 流水线（模块 12）**
`RouterAgent → parallel(时间线检索 Agent, 文档检索 Agent) → AnswerAgent(流式) → MemoryCuratorAgent(异步)`。
用 AgentScope 的 pipeline/`MsgHub` 共享一次请求的 `sources` 元数据；AnswerAgent 只做融合与生成，检索 Agent 各自带自己的工具和预算。

**C. Handoff + MCP + Skills（展示加分项）**
- handoff：问题明显属于「时间线管理」时交给 `TimelineAgent`，它可以直接调写接口（仍需用户确认写操作）。
- MCP：把 `search_documents` / `get_timeline_by_date` 暴露成 MCP server，Claude Desktop 等外部客户端可复用同一套个人上下文能力。
- Skills：把「周报生成」「面试复盘」等流程沉淀为 skill 文件，Agent 按需加载，不再塞进主 prompt。

## 7. 一次提问的最终时序

1. `ChatController` 收问题，预检 key、长度，生成 `requestId`。
2. `AgentOrchestrator` 从 Session 恢复最近 N 轮 + 摘要。
3. Agent 判断：直接答 / 先查时间线 / 先查文档 / 两者都查（可并行发工具调用）。
4. 工具执行：原有检索链路返回结构化命中，附带 `degraded`、耗时、分数。
5. Agent 决定是否补查（最多 4 轮），仍无证据则明确说「没有找到相关记录」。
6. `AgentEventToSseBridge` 先发 `start` + `sources`（工具结果去重、排序、编号），再流转 `delta`。
7. 生成结束发 `done`（含 usage），异常发 `error`（含 `partial`）。
8. 异步 `MemoryCuratorAgent` 提炼 0–2 条 PENDING 候选，前端记忆页提示确认。
9. 用户确认 → 转 FACT；下次对话 `search_memory` 可召回。

## 8. 不变式（重构不能破的线）

- **SSE 契约不变**：`start / sources / delta / done / error` 字段与顺序保持；新增事件只能是可选、可忽略的。
- **三源分别标注**：时间线 `[Tn]`、文档 `[Dn]`、外部源单独标记；`sources.items` 与正文编号一一对应。
- **事实与推断分离**：工具只返回事实；推断只能进 PENDING，确认后才可信。
- **降级可见**：检索失败/降级时 Agent 继续回答并标注，不能静默当成「没有记录」。
- **可回滚**：配置 `agent.orchestrator=legacy|agentscope`，出问题一条配置切回旧链路。
- **可测**：工具层不依赖 LLM，纯单测；Agent 层用 Mock Model 回放固定工具调用序列。

## 9. 落地步骤与验收

| 步骤 | 产出 | 验收 |
|---|---|---|
| 1. 依赖冒烟 | AgentScope Java 引入、`ChatClientModelAdapter`、一个 hello agent | 单测能跑通一次工具调用；Boot 4 启动无冲突 |
| 2. 工具化 | 4 个 Tool + 参数 schema + 错误语义 | 工具单测全绿；降级路径有覆盖 |
| 3. 单 Agent 收口 | `AgentOrchestrator` + 事件桥 + `legacy` 开关 | 同一批问题新旧链路答案质量对比；SSE 抓包一致 |
| 4. 多 Agent | Router/Answer/Curator 流水线 | 一次请求工具调用 ≤4 次，P95 延迟可接受 |
| 5. 展示层 | MCP server、Skills、评测集回归 | 外部客户端能调通；`eval/` 指标不退化 |

## 10. 主要风险

- **版本兼容**：AgentScope Java 迭代快（v1/v2 文档并存），Spring Boot 4 组合需先做冒烟；锁死一个版本，不追新。
- **延迟与 token 上升**：ReAct 多轮会放大调用次数；用「规则路由快速通道 + 工具结果摘要 + maxIters 上限」压住。
- **流式映射**：Agent 的 thinking/tool 事件与 `delta` 混流容易乱序；桥接层做单线程串行化，先只透传 answer 文本。
- **测试成本**：多 Agent 不确定性高；坚持「工具纯单测 + Agent Mock 回放 + 端到端少量脚本」三层，不做全量 mock。
- **不要为了框架而框架**：如果第 3 步的对比显示质量/延迟没有提升，就停在「工具化 + 单 Agent」，不强行上多 Agent。

## 11. 最终演示脚本（一句话版）

> 「我上个月学了什么？结合我的笔记给我下一步建议。」

Agent 自主决定：查 9 月时间线 → 查「学习笔记」相关文档 → 融合后回答，正文带 `[T1][D2]` 标注 → 末尾提出两条待确认记忆（「你在学 Kafka」）→ 用户确认 → 下次提问能直接引用这条事实。前端时间线页、文档页、记忆页全部照常工作。

## 12. 参考

- AgentScope Java 文档：<https://java.agentscope.io/v2/zh/docs/quickstart>
- 多 Agent（pipeline / handoff）：<https://java.agentscope.io/v1/zh/docs/multi-agent/handoffs>
- 设计底稿：`docs/design/overview.md`；Ragent 对照：`docs/reference/ragent-implementation.md`
