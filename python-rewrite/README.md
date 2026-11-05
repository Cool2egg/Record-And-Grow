# RecordAndGrow

个人上下文 Agent：**FastAPI + 两段式检索/回答 + PostgreSQL 单库**。
业务数据、向量（pgvector）、关键词检索都在同一个 PG 实例里，前端零改动对接。

- 技术选型：[docs/tech-stack.md](docs/tech-stack.md)
- 接口契约：[docs/contract/openapi.yaml](docs/contract/openapi.yaml)、[docs/contract/sse-events.md](docs/contract/sse-events.md)、[docs/contract/parity-checklist.md](docs/contract/parity-checklist.md)

## 快速开始

```bash
# 1. 配置：复制并填入密钥（真实密钥永不入库）
cp .env.example .env

# 2. 一条命令起完整系统：postgres + app + 前端(nginx 同源反代)
docker compose up -d
# 前端 http://localhost:8081  后端 http://localhost:8080

# 3. 或者只起依赖、本地跑后端
uv sync --extra dev
docker compose up -d postgres
uv run alembic upgrade head
uv run uvicorn app.main:app --reload --port 8080
```

验证：

```bash
curl http://localhost:8080/api/health
curl -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"question":"我最近在学什么"}'
```

前端同源部署：nginx 托管 `frontend/dist`，把 `/api` 与 `/mcp` 反代到 `app:8080`（见 [deploy/nginx.conf](deploy/nginx.conf)），因此后端不需要 CORS。

## 目录结构

```
app/
  api/          # 路由、SSE、全局异常处理、MCP
  agent/        # 两段式 Agent（检索 → 回答）、Prompt、工具、意图路由
  rag/          # 模型客户端、时间窗、关键词、RRF、证据闸门、检索编排
  document/     # 解析、切块、存储
  services/     # 时间线 / 会话 / 记忆 / 文档 / 图谱 / 技能 / 健康检查
  models/       # SQLAlchemy 模型
  schemas/      # 对外 JSON 契约（camelCase）
  core/         # 配置、错误码、响应信封、日志
alembic/        # 迁移
tests/          # 单测（离线）+ 集成测试（Docker，默认跳过）
```

## 契约要点

- 所有 JSON 接口返回 `{code, message, data}`；`code=0` 成功，业务码与 HTTP 状态码分离。
- SSE：`start → step* → sources → delta* → done`，`sources` 固定在首个 `delta` 之前；15 秒心跳 `: ping`。
- 时间：对外一律 UTC（`+00:00`），客户端自行转本地时区。
- 文档上传：新文档 `202`，命中已有内容 `200`，响应里 `deduplicated=true`。

详见 [docs/contract/sse-events.md](docs/contract/sse-events.md)。

## 功能开关（.env）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AGENT_ENABLED` | true | 两段式 Agent；关闭后走经典检索链路 |
| `GRAPH_ENABLED` | true | 知识图谱抽取与检索 |
| `MEMORY_ENABLED` | true | 长期记忆三态 |
| `MEMORY_EXTRACTION_ENABLED` | true | 回答后异步抽取候选记忆 |
| `MCP_ENABLED` | true | `/mcp` stateless server |
| `SKILLS_ENABLED` | true | `skills/` 热加载 |
| `FUSION_ENABLED` | false | 向量 + 关键词 RRF 融合 |
| `KEYWORD_ENABLED` | false | 关键词通道（pg_search BM25，缺失时回退 jieba + ILIKE） |
| `ROUTING_ENABLED` | false | 意图路由（多一次小模型调用） |
| `MCP_AUTH_TOKEN` | 空 | 非空时 `/mcp` 要求 `Authorization: Bearer <token>` |

## 部署与运维

- **同源部署**：`frontend` 用 nginx 托管静态文件，`/api`、`/mcp` 反代到 `app:8080`；SSE 已在 nginx 关闭缓冲并放宽到 300s。
- **非 root 运行**：镜像使用 `appuser`；数据目录是具名卷 `appdata`，技能目录只读挂载。
- **可复现构建**：镜像拷入 `uv.lock` 并用 `uv sync --locked`。
- **请求追踪**：每个 HTTP 响应带 `X-Request-Id`；SSE 的 `requestId` 复用同一个值，日志里可直接串联。
- **优雅停机**：入库、记忆抽取、摘要等后台任务登记在 `app/core/tasks.py`，停机时先等 10 秒再取消；入库另有对账兜底。
- **备份**：

```bash
./deploy/backup.sh ./backups
```

- **公网暴露**：接口本身没有登录（单用户前提），请放在带鉴权的反代/VPN 后面；`/mcp` 至少设置 `MCP_AUTH_TOKEN`。

## 已知边界

- `pg_search` 是 AGPL-3.0；未建 pg_search 索引时关键词通道自动回退 jieba 分词 + ILIKE（命中词数评分），不依赖扩展也能跑。
- 文档解析支持 PDF / Word / Markdown / 纯文本；扫描件 PDF 需要外挂 OCR。解析受 `document.parse-timeout` 约束。
- 上传边读边落盘并计数，超出大小上限立即可拒，不会先整块读进内存。
- CI 中 mypy 是提示性检查（`continue-on-error`），未作为合并门槛。

## 测试

```bash
uv run pytest -q                                        # 单测（离线，无需数据库）
RUN_INTEGRATION=1 uv run pytest -q tests/integration    # Docker/testcontainers 起 pgvector
# 已有数据库时：
RUN_INTEGRATION=1 INTEGRATION_DATABASE_URL=postgresql+asyncpg://user:pass@127.0.0.1:5432/db \
  uv run pytest -q tests/integration
```

### 端到端联调（无需真实模型密钥）

仓库带一个本地 OpenAI 兼容桩服务（[scripts/stub_model_server.py](scripts/stub_model_server.py)），
覆盖普通回答、SSE 流式、工具调用与向量，可完整验证契约与业务流程（31 项断言）：

```bash
# 1. 起库并迁移（本机已有 pgvector 镜像时）
docker run -d --name rag-e2e-pg -p 5432:5432 \
  -e POSTGRES_DB=recordandgrow -e POSTGRES_USER=recordandgrow -e POSTGRES_PASSWORD=recordandgrow \
  pgvector/pgvector:pg16
uv run alembic upgrade head

# 2. 起桩模型服务
uv run uvicorn stub_model_server:app --app-dir scripts --port 9099

# 3. 应用指向桩服务
DEEPSEEK_BASE_URL=http://127.0.0.1:9099 DEEPSEEK_API_KEY=test \
DASHSCOPE_BASE_URL=http://127.0.0.1:9099 DASHSCOPE_API_KEY=test \
uv run uvicorn app.main:app --port 8080

# 4. 跑联调（桩模式，无需密钥）
uv run python scripts/e2e_smoke.py

# 5. 真实模型模式（.env 已配好密钥时，跳过桩特有断言）
E2E_LIVE=1 uv run python scripts/e2e_smoke.py
```
