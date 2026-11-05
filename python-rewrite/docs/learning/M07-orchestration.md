# M07 编排

- 两段式：检索 Agent 只负责找证据（最多 4 轮工具调用），回答 Agent 只负责按证据写答案；这样 `sources` 能稳定地出现在首个 delta 之前。
- 研究阶段的每次工具调用都产出 `step` 事件（工具名、入参、命中数、耗时、错误），先于 `sources` 发给前端。
- 证据上下文 `AgentToolContext` 统一编号：时间线 `T`、文档 `D`、记忆 `M`、图谱 `G`；`sources` 与 Prompt 用同一份编号，保证引用一一对应。
- 同一请求内相同 query 的文档/图谱检索结果缓存，避免 ReAct 多轮重复调用放大 embedding 成本。
- 任一步失败不静默：流内异常统一转成 `error` 事件；已经发出的 delta 用 `partial=true` 标记。
- 计划中的 LangGraph `StateGraph` 用显式循环实现（研究循环 + 回答阶段），状态 schema 即 `AgentToolContext`；图结构可 mermaid 化：`start → research(loop) → answer → end`。
