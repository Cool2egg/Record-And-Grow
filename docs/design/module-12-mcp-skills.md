# 模块十二：ReAct / MCP / Skills

> ReAct 已并入模块十一的检索 Agent；本模块补上「把能力暴露出去」（MCP）与「把流程沉淀下来」（Skills）。

## 1. MCP Server

### 形态

- 官方 MCP Java SDK 0.17.2；
- **stateless HTTP** transport，挂载 `POST /mcp`；
- 只暴露只读工具：`search_timeline`、`search_documents`、`search_memory`、`search_graph`；
- 不暴露写接口（时间线写入、记忆确认仍走 REST）。

### 为什么 stateless

- 单用户本机使用，不需要会话级资源订阅；
- stateless 不需要维护 SSE 长连接与 session 表，重启即恢复；
- 外部客户端（Claude Desktop / AgentScope MCP 客户端）每次调用自带参数，足够。

### 一个坑

`JacksonMcpJsonMapper(new ObjectMapper())` 会缺 MCP 协议的 mixin，`initialize` 直接报 `Invalid message format`。
必须用 `McpJsonMapper.createDefault()`，它会加载 SDK 自带的 Jackson 模块。

### 工具复用

`McpToolService` 不重新写检索，而是构造临时 `AgentToolContext` 后复用 `ChatAgentTools`：
保证 MCP 与 Agent 查到的内容、脱敏、降级语义完全一致。

### 安全

- 无鉴权、无租户隔离；
- 默认只应监听 `127.0.0.1` 或内网；
- 生产化需要加 token、限流与审计。

## 2. Skills

### 文件格式

`skills/*.md`，支持 front matter：

```markdown
---
name: 周报生成
description: 把一段时间的时间线整理成周报
triggers: 周报, 本周总结, 总结
---
正文：步骤、口吻、输出结构……
```

也支持 `# 标题` + `> 触发词：…` 的简写。

### 匹配与注入

- 启动时扫描目录（`SkillRegistry`），`POST /api/skills/reload` 热加载；
- 问题包含任意 trigger 即命中，按命中数排序，最多注入 2 个；
- 注入位置：回答 Prompt 的「【技能指引】」段，位于证据之后、问题之前；
- 不做语义匹配：宁可少注入，也不要把无关技能塞进 Prompt。

### 内置技能

| 文件 | 触发词 | 作用 |
|---|---|---|
| `skills/周报生成.md` | 周报、总结、月报 | 三段式周报 + 引用 |
| `skills/面试复盘.md` | 面试、复盘 | 事实/感受分离 + 改进项 |
| `skills/学习计划.md` | 学习计划、怎么学 | 已掌握/待补/下一步 |

## 3. 为什么不用 AgentScope SkillBox

AgentScope 2.x 自带 `SkillBox` / `AgentSkillRepository`，能力更全（动态加载、代码执行）。
当前选择自建是因为：

- 本项目技能只是 Prompt 片段，不需要代码执行与沙箱；
- 自建 100 行可测、可热加载、文件即资产；
- 后续要换 SkillBox，只需把 `SkillRegistry.match` 的结果转成它的 skill 对象。

## 4. 测试

- `McpServerTest`：真实 Tomcat + `initialize` + `tools/list`；
- `SkillRegistryTest`：front matter、标题简写、触发词匹配、热加载；
- 手工：`curl POST /mcp`（initialize / tools/call），`GET /api/skills`。
