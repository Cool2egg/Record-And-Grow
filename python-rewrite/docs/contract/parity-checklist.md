# 功能对等清单

> 状态：`未开始 / 实现中 / 已验收`。
> **已验收** 指本轮真实跑过：数据库 + HTTP 接口 + 本地桩模型服务（`scripts/stub_model_server.py`），
> 断言在 `scripts/e2e_smoke.py`（31 项）。桩模型验证的是契约与流程，**不验证真实模型质量**；
> 真实 DeepSeek / DashScope 与前端页面的联调仍需要密钥后复跑。
>
> **真实模型复跑记录（2026-11-04）**：配置 `.env` 后 `E2E_LIVE=1` 跑通 25 项
> （健康、时间线、上传→真实向量→READY、SSE 顺序与 sources、非流式、MCP）；
> 图谱用一篇含实体关系的文档验证到 4 节点 / 3 条边，`/api/graph/search` 能命中；
> 记忆抽取本轮未产出候选，故 confirm 链路未在真实模型下复验（桩模式已验证）。

## HTTP 接口

| 模块 | 方法 | 路径 | 契约要点 | 状态 |
| --- | --- | --- | --- | --- |
| 系统 | GET | `/api/health` | 真实 `SELECT 1` + pgvector 检查；失败 503 | 已验收（E2E） |
| 系统 | GET | `/api/system` | 版本、启动时间、模型 key 状态、技能列表 | 已验收（E2E） |
| 技能 | GET | `/api/skills` | 技能列表（name/description/triggers/body/source） | 实现中（冒烟返回空列表） |
| 技能 | POST | `/api/skills/reload` | 热加载，返回 `{loaded}` | 实现中 |
| 时间线 | POST | `/api/timeline` | 创建；`endTime` 可空 | 已验收（E2E） |
| 时间线 | GET | `/api/timeline` | `start/end` ISO-8601、分页、start_time 倒序 | 已验收（E2E） |
| 时间线 | GET | `/api/timeline/{id}` | 404 语义 | 实现中 |
| 时间线 | PUT | `/api/timeline/{id}` | `endTime: null` 清空 | 已验收（E2E） |
| 时间线 | DELETE | `/api/timeline/{id}` | 软删 | 已验收（E2E） |
| 会话 | GET | `/api/conversations` | 按 updated_at 倒序分页 | 实现中 |
| 会话 | POST | `/api/conversations` | 标题缺省「新对话」 | 实现中 |
| 会话 | GET | `/api/conversations/{id}` | 404 语义 | 实现中 |
| 会话 | GET | `/api/conversations/{id}/messages` | 按 id 升序分页，`sources` 原样 JSON 字符串 | 已验收（E2E） |
| 会话 | DELETE | `/api/conversations/{id}` | 软删 | 已验收（E2E） |
| 对话 | POST | `/api/chat` | 非流式，返回完整回答 + sources | 已验收（E2E，桩模型） |
| 对话 | POST | `/api/chat/stream` | SSE，见 sse-events.md | 已验收（E2E，桩模型） |
| 记忆 | GET | `/api/memories` | `type` 过滤；非法 type → 40000 | 已验收（E2E） |
| 记忆 | GET | `/api/memories/pending` | 待确认列表 | 已验收（E2E） |
| 记忆 | GET | `/api/memories/search` | 只返回 FACT/INFERRED | 实现中 |
| 记忆 | POST | `/api/memories` | 手动创建 FACT | 实现中 |
| 记忆 | POST | `/api/memories/{id}/confirm` | PENDING → FACT/INFERRED，可改内容与类型 | 已验收（E2E） |
| 记忆 | POST | `/api/memories/{id}/reject` | 软删 | 实现中 |
| 记忆 | DELETE | `/api/memories/{id}` | 软删 | 实现中 |
| 文档 | POST | `/api/documents` | 新文档 202、去重 200；`deduplicated` 字段 | 已验收（E2E） |
| 文档 | GET | `/api/documents` | 分页、`status` 过滤 | 实现中 |
| 文档 | GET | `/api/documents/{id}` | 不暴露 storagePath/normalizedText | 已验收（E2E） |
| 文档 | GET | `/api/documents/{id}/chunks` | 按 chunk_index 升序 | 已验收（E2E） |
| 文档 | GET | `/api/documents/{id}/chunks/{chunkId}` | 返回目标块 + 所在页 + 总数 | 已验收（E2E） |
| 文档 | POST | `/api/documents/{id}/reindex` | 处理中 → 40900；成功 202 | 实现中 |
| 文档 | DELETE | `/api/documents/{id}` | 软删 + 清块 + 删文件 | 已验收（E2E） |
| 图谱 | GET | `/api/graph/search` | 1 跳实体关系；`searched/degraded` | 已验收（E2E） |
| 图谱 | GET | `/api/graph/stats` | `{nodes, edges, enabled}` | 已验收（E2E） |
| 图谱 | POST | `/api/graph/reindex` | 返回 `{indexed}` | 实现中 |
| MCP | POST | `/mcp` | JSON-RPC：initialize / tools/list / tools/call | 已验收（E2E） |

## SSE 事件

| 事件 | 关键约束 | 状态 |
| --- | --- | --- |
| `start` | 带 `requestId`；无会话时回传新 `conversationId` | 已验收（E2E） |
| `step` | seq 从 1；全部在 sources 之前；失败 ok=false | 已验收（E2E） |
| `sources` | 恰好一次；首个 delta 之前；items 与 retrieval 计数满足不变式 | 已验收（E2E） |
| `delta` | 只追加 | 已验收（E2E） |
| `done` | 与 error 互斥终结；带 usage | 已验收（E2E） |
| `error` | 与 done 互斥终结；`partial` 表示已有部分输出 | 已验收（E2E，上游 500） |
| 心跳 | 15s `: ping`，前端忽略注释行 | 实现中（本轮未观察到长空闲） |

## 本轮未覆盖（需真实环境或后续补测）

- 真实 DeepSeek / DashScope：模型质量、限流、超时、降级（`documentsDegraded` / `degradeReason`）。
- 前端六个页面的人工验收；`/api/skills` 热加载、`documents/reindex`、`memories/reject`、`graph/reindex` 的专项用例。
- 15 秒心跳与 40 秒前端看门狗的配合。
- 评测对比报告 `docs/report/parity.md`。

## 如何验收（真实密钥）

```bash
cp .env.example .env    # 填入 DEEPSEEK_API_KEY / DASHSCOPE_API_KEY
docker compose up -d    # 前端 8081，后端 8080
uv run python scripts/e2e_smoke.py   # 改指向真实服务：去掉桩相关环境变量即可
```

判定标准：前端六个页面（聊天 / 时间线 / 文档 / 记忆 / 图谱 / 状态）不改一行代码可用；
SSE 顺序与不变式符合 `sse-events.md`；对外时间全部为 UTC。
