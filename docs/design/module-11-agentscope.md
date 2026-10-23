# 模块十一：AgentScope 编排

> 目标：把模块四手写的六步流水线，换成 AgentScope 驱动的「检索 Agent + 回答 Agent」，同时保持 SSE 契约不变。

## 1. 为什么是两段式，而不是单个 ReActAgent

单个 ReActAgent 边查边写会遇到两个硬问题：

1. **SSE 顺序**：契约要求 `sources` 先于第一个 `delta`；单 Agent 在第一次工具调用前就可能开始输出文字，sources 无法保证先到。
2. **引用稳定性**：模型在检索轮里写的「我查一下」会混进最终答案；编号 `[T1]` 也可能在后续工具调用后失效。

因此拆成：

```
检索 Agent（ReAct + 工具，maxIters=4，不输出给用户）
    ↓ 收集证据（编号在 Java 侧分配）
回答 Agent（无工具，maxIters=1，流式输出）
```

这样 `sources` 在回答 Agent 启动前固定，delta 只来自最终答案。

## 2. 组件

| 类 | 职责 |
|---|---|
| `AgentChatService` | 入口、两段式执行、SSE 桥接、会话落库、异步后处理 |
| `AgentPrompts` | 检索 / 回答两套系统 Prompt，证据区与技能区拼装 |
| `AgentToolContext` | 单请求证据账本：编号、去重、计数、`sources` 元信息 |
| `ChatAgentTools` | 6 个 `@Tool`：时间线、按日、文档、记忆、图谱、提交候选记忆 |
| `EvidenceItem` | 一条证据：ref / kind / 正文 / 出处，负责 Prompt 行与 SourceItem 转换 |
| `AgentConfig` | `OpenAIChatModel`（主 + 兜底）、虚拟线程执行器 |
| `AgentSseSession` | start/sources/delta/done/error、心跳、取消、串行写 |

## 3. 模型接入

- 使用 `agentscope-extensions-model-openai` 的 `OpenAIChatModel`，端点 `/v1/chat/completions`；
- 主模型 + `fallbackModel`（`recordandgrow.ai.chat.fallback-*`）；
- 不套 `FailoverChatClient`：AgentScope 自带重试与兜底，两层重试会放大延迟；
- 无 key 时 `AgentChatService.isEnabled()=false`，`ChatService` 自动回落旧链路。

## 4. 一次请求的时序

```
POST /api/chat/stream {question, conversationId?}
  ├─ 校验 key / 长度
  ├─ 取或建会话；先读历史，再写本轮 user 消息
  ├─ 返回 SseEmitter，异步任务：
  │    ├─ send start {requestId, conversationId}
  │    ├─ 检索 Agent（工具调用；失败则固定双路兜底）
  │    ├─ send sources（证据已定稿）
  │    ├─ 回答 Agent 流式输出 → delta…
  │    ├─ send done {finishReason, usage}
  │    ├─ 落 assistant 消息（含 sources JSON）
  │    └─ 异步摘要 + 记忆提炼
  └─ 客户端断开 → cancelled → 取消订阅 / 停止发送
```

## 5. 降级与失败语义

| 阶段 | 失败 | 行为 |
|---|---|---|
| 预检 | 无 key | `AiException(NOT_CONFIGURED)` → 503，不建流 |
| 检索 Agent | 超时 / 模型错误 | 回退固定双路检索，回答继续 |
| 单个工具 | DB / embedding 错误 | 工具返回 `{"error":...}`，Agent 可换关键词重试 |
| 文档检索 | embedding 降级 | `DocumentRetrievalResult.degraded` 透传到 sources |
| 回答 Agent | 超时 / 空输出 | 发 `error` 事件，`partial` 由是否已发 delta 决定 |
| 落库 | 失败 | 只记日志，不回滚已发给客户端的回答 |

## 6. SSE 契约

- `start` 新增 `conversationId`（旧客户端可忽略）；
- `sources.retrieval` 新增 `memoryIncluded` / `graphIncluded`，`totalIncluded` 覆盖四类来源；
- 事件顺序固定：`start → sources → delta* → done`，异常为 `error`；
- 心跳每 15s 一条注释，避免代理超时。

## 7. 配置

```yaml
recordandgrow:
  agent:
    enabled: true
    research-max-iters: 4
    research-timeout: 45s
    answer-timeout: 120s
    max-evidence-chars: 7000
    max-evidence-items: 60
    memory-tools: true
    graph-tools: true
    propose-memory: true
```

## 8. 测试

- `AgentScopeSmokeTest`：ReAct + 反射工具注册的最小闭环；
- `OpenAiModelWireMockTest`：真实 OpenAI 协议 + 流式 `tool_calls` 解析；
- `AgentChatServiceTest`：真实 ReActAgent + 真实工具注册，mock 模型与检索，验证证据编号、sources、落库；
- `AgentToolContextTest`：编号稳定、分节输出、预算截断、去重；
- `McpServerTest`：真实 Tomcat 下的 MCP 握手与 tools/list。

## 9. 已知限制

- 两段式比单 Agent 多一次模型调用，首包延迟更高；
- 检索 Agent 不调工具时不会自动兜底检索（只会在模型失败时兜底），依赖 Prompt 约束；
- 没有多 Agent handoff / pipeline；当前收益不足以抵消复杂度，留作后续。
- 图谱默认是 Postgres 实现，Neo4j / LightRAG 通过 `GraphStore` 接口替换，见模块七设计稿。
