# 技术选型（Python 重写版）

> 状态：已确认（2026-11-03）。本文件是新仓库的 `docs/tech-stack.md` 初稿，建仓库后整体搬入。

## 一句话

FastAPI + LangGraph + PostgreSQL 单库的个人上下文 Agent。业务数据、向量、关键词检索都在同一个 PG 实例里。

## 分层

| 层 | 选型 | 用途 / 理由 |
| --- | --- | --- |
| 语言 | Python 3.12 | 3.13 生态仍有零星库未跟上，3.12 最稳 |
| Web | FastAPI + Uvicorn | 原生 async，StreamingResponse 直接支撑 SSE |
| SSE | sse-starlette | 事件格式、心跳、断连处理都包了 |
| 校验 | Pydantic v2 + pydantic-settings | 请求/响应模型 + 配置，FastAPI 天生配套 |
| 依赖管理 | uv | 快，锁文件进版本库，保证半年后还能装回原样 |
| 数据库 | PostgreSQL 18 | 业务数据 + 向量 + 关键词，一个实例 |
| 向量 | pgvector | HNSW 索引，`<=>` 运算符直接写在 SQL 里 |
| 关键词 | ParadeDB pg_search | Tantivy BM25，内置 jieba tokenizer |
| ORM | SQLAlchemy 2.0 async + asyncpg | 异步 ORM |
| 迁移 | Alembic | 替代 Flyway |
| 模型层 | langchain 1.4 + langchain-openai | `init_chat_model` 打 OpenAI 兼容接口 |
| 编排 | langgraph 1.2 | StateGraph 显式表达 Agent 流转 |
| Chat 模型 | DeepSeek（deepseek-flash / deepseek-v4-pro） | 已有账号，OpenAI 兼容 |
| Embedding | text-embedding-v4，1024 维 | OpenAI 兼容 |
| 重排 | API 版 rerank（硅基流动 / 通义） | 不在本地跑模型 |
| 可观测 | Langfuse | LangChain 有现成 callback |
| 文档解析 | pymupdf（PDF）+ python-docx（Word）+ md/txt | 覆盖主力格式，复杂 PDF 走定制后处理 |
| MCP | 官方 mcp Python SDK | 和 Java 版行为对齐 |
| 异步任务 | DB 状态表 + 后台协程 | 不引 Celery / Redis，重启时扫未完成任务重跑 |
| 测试 | pytest + pytest-asyncio + httpx | 单测 + 接口测试 |
| 集成测试 | testcontainers-python | 拉真 PG，对应 Java 版 Testcontainers |
| 质量 | ruff（格式 + lint）、mypy（类型）、pre-commit | 提交前自动跑 |
| CI | GitHub Actions | PR 跑 ruff / mypy / pytest |
| 前端 | React 18 + Vite（v0.9.9 原样复用） | 靠锁死的 API/SSE 契约，零改动 |
| 部署 | docker compose（app + postgres） | 两个服务，自建 |

## 明确不引入

- Milvus —— 向量放 pgvector，业务数据和向量同事务
- Elasticsearch —— 关键词放 pg_search，省掉一个系统
- Redis / Celery —— 当前量级用 DB 状态表就够
- Tika / unstructured —— pymupdf + python-docx 覆盖主力格式
- 额外的 vector store 抽象层 —— 直接写 SQL，少一层难 debug 的封装

## 需要留意的边界

- `pg_search` 是 AGPL-3.0，自用没问题，闭源分发要评估
- 分词器或自定义词典一改必须重建索引
- jieba 对项目名、人名的切分需要验证是否支持用户词典
- DeepSeek / Qwen 的模型名会变，配置集中在环境变量里
