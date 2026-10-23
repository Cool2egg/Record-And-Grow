# 模块七至十二学习笔记

## 1. AgentScope Java 2.x 的接入要点

### 1.1 依赖

- `io.agentscope:agentscope-core:2.0.4`
- `io.agentscope:agentscope-extensions-model-openai:2.0.4`

core 自带 Reactor、Jackson 2、MCP SDK、OkHttp；OpenAI 扩展里已经有 `DeepSeekModelProvider`，
所以 DeepSeek 不需要自己写 wire。

### 1.2 最小 ReAct 闭环

```java
ReActAgent agent = ReActAgent.builder()
    .name("researcher")
    .sysPrompt("...")
    .model(openAIChatModel)
    .toolkit(toolkit)
    .maxIters(4)
    .build();

Msg result = agent.call("问题", RuntimeContext.empty()).block(timeout);
```

工具就是一个普通对象 + `@Tool` / `@ToolParam`，`toolkit.registerTool(obj)` 即可。

### 1.3 踩坑：ToolUseBlock 的参数在 content 里

单测里手工构造模型响应时，只写：

```java
ToolUseBlock.builder().id("1").name("echo").input(Map.of("text","hi")).build()
```

ReAct 会执行工具，但参数校验报「未找到参数名 query」。原因是工具调用参数实际从
`ToolUseBlock.content` 的 JSON 字符串解析，`input` 只是展示用。正确写法：

```java
.content("{\"text\":\"hi\"}")
```

真实模型返回的 tool_calls 一定带 content，所以线上没问题；这是**写测试时**才会踩的坑。

### 1.4 踩坑：可选参数

`@ToolParam(required = false)` 的 `Integer days` 在模型不传时仍可能触发参数校验失败。
最后把「最近 N 天」合并进 query，让 `TimeWindowParser` 解析，工具签名保持单参数。
结论：**给 LLM 的工具参数越少越稳**。

## 2. 两段式编排与 SSE

单 Agent 边查边写无法保证 `sources` 先于第一个 `delta`，也会让「我查一下」混进最终答案。
拆成：

1. 检索 Agent：工具调用，结果只进 Java 的 `AgentToolContext`；
2. 回答 Agent：无工具，流式输出。

编号在 Java 侧按 `T/D/M/G` 分配，Prompt 与 SSE `sources` 共用同一份 `EvidenceItem` 列表，
从结构上保证一一对应，而不是靠模型自觉。

## 3. MCP Stateless Server

- 官方 SDK 的 `HttpServletStatelessServerTransport` + `McpStatelessSyncServer`；
- 注册为 `ServletRegistrationBean`，路径 `/mcp`；
- **必须用 `McpJsonMapper.createDefault()`**；自己 `new JacksonMcpJsonMapper(new ObjectMapper())`
  会因为缺协议 mixin 把 `initialize` 判成 `Invalid message format`；
- 工具实现复用 `ChatAgentTools`，MCP 与 Agent 不会出现两套检索逻辑。

## 4. 知识图谱为什么从 Neo4j 改成 Postgres

- 规模：单用户、几千条边；
- 查询：实体名匹配 + 1 跳，不需要 Cypher 的可变长路径；
- 成本：少一个中间件、少一套运维；
- 可替换：`GraphStore` 接口隔离，换 Neo4j 只加实现。

这是一个「按规模选型」的例子：面试时讲清楚**为什么不用**比堆技术栈更加分。

## 5. 记忆三态的关键约束

- AI 只能写 PENDING；
- 只有 FACT / INFERRED 进检索；
- 确认时用 `proposed_type` 决定落点；
- 去重靠 `normalized` + 部分唯一索引；
- 摘要用「水位 + 带条件 UPDATE」，避免并发覆盖。

## 6. 测试策略

- Agent 层：真实 `ReActAgent` + 真实工具 + fake `Model`，验证编排而不是模型；
- 协议层：WireMock 回放 OpenAI 流式 tool_calls，验证 SDK 适配；
- MCP：真实 Tomcat 起服务，发 JSON-RPC；
- 真库：Testcontainers，默认用 `-Pintegration` 排除，保证无 Docker 时 `mvn test` 仍绿。
