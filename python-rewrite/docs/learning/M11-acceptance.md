# M11 联调与验收

- 验收基准：前端 v0.9.9 不改一行；六个页面（聊天 / 时间线 / 文档 / 记忆 / 图谱 / 状态）全部可用。
- 本轮完成：契约冻结、全部接口与 SSE 实现、单测 41 项通过（离线）、OpenAPI 导出 27 条路径；compose 增加前端服务与 nginx 同源反代；上传改流式落盘并受解析超时约束；关键词通道补 jieba 回退；请求级 requestId 与优雅停机；MCP 可选 Bearer 鉴权；CI 增加集成测试 job。
- **真实跑过的联调**：起 PostgreSQL + 应用 + 本地桩模型服务，`scripts/e2e_smoke.py` 31 项断言全过（时间线 CRUD/UTC、上传→解析→切块→向量→READY、图谱异步抽取与 1 跳查询、SSE 顺序与 sources 不变式、step 先于 sources、error 终止、消息落库、记忆异步抽取→PENDING→confirm、非流式对话、MCP tools/call）；集成测试对真实库跑通。
- 联调中发现并修复两处配置 bug：① `AiChatSettings.base_url` 漏配环境变量别名，`DEEPSEEK_BASE_URL` 不生效（图谱抽取打到了真实服务）；② 嵌套配置类不读 `.env`，本地只配文件时密钥被忽略——已改用显式读 `.env` 的基类，并加了回归测试。
- **真实模型复跑**：`.env` 配置 DeepSeek + DashScope 后，`E2E_LIVE=1` 25 项通过（真实向量入库、SSE 顺序、非流式、MCP）；另用一篇含实体关系的文档验证图谱抽取得到 4 节点 / 3 条边。记忆抽取本轮未产出候选，confirm 链路仅在桩模式验证。
- 未完成：前端页面人工验收、`docs/report/parity.md` 评测对比。
- 对等清单见 `docs/contract/parity-checklist.md`（逐条状态与验收命令）。
- 部署注意：公网暴露必须挂鉴权反代；`/mcp` 至少设置 `MCP_AUTH_TOKEN`；备份用 `deploy/backup.sh`。
