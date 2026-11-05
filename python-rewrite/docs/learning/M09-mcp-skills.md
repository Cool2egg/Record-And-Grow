# M09 MCP 与 Skills

- MCP 采用 stateless JSON-RPC：`initialize / ping / tools/list / tools/call`，通知（无 id）返回 202；只暴露只读工具，`propose_memory` 不对外。
- 工具描述直接复用 Agent 的 `TOOL_DEFINITIONS`，一份定义两处使用，避免 MCP 与内部工具漂移。
- Skills 从目录扫描 Markdown，frontmatter 解析 `name/description/triggers`；命中触发词时把技能正文注入回答 Prompt，最多 2 个。
- `POST /api/skills/reload` 热加载；损坏文件只记 warning，不拖垮服务。
- 注意：技能目录默认 `./skills`，仓库里放的是 `skills.example/`，真实技能不入库。
