# Python 重写施工计划

> 状态：已确认（2026-11-03）。本文件是新仓库的 `docs/plan/rewrite-plan.md` 初稿。
> 上游：[docs/tech-stack.md](../tech-stack.md)

## 一、总则

### 仓库策略

新仓库 `RecordAndGrow-py` 为主仓库。Java 版打 `v1.0` tag 后进入只读状态，README 顶部链接到主仓库；主仓库 README 顶部链接回 v1 实现。只 pin 主仓库。

不在原仓库原地替换，不 force push 覆盖历史。

### 完成定义（每个模块通用的 DoD）

1. 接口与 `docs/contract/` 中的契约一致，前端不改一行
2. 单测 + 集成测试通过，CI 全绿
3. `docs/learning/<模块>/` 下有学习记录，README 挂上链接
4. 合并进 `main`，打 tag，release note 写"这版能做什么"

四条缺一条不算完成。

### 分支与提交

`module/xx-<name>` 分支，一个模块一个 PR。提交信息用 Conventional Commits（`feat:` / `fix:` / `refactor:` / `docs:` / `test:` / `chore:`）。

### 契约先行

M01 产出的 `openapi.yaml`、`sse-events.md`、`parity-checklist.md` 是后续所有实现的唯一依据。实现与契约冲突时，先改契约再改代码，不允许"代码先跑起来回头补契约"。

## 二、模块顺序与依赖

| 编号 | 模块 | 依赖 | 规模 |
| --- | --- | --- | --- |
| M00 | 仓库骨架与工程基线 | — | S |
| M01 | 契约冻结 | M00 | M |
| M02 | 时间线 | M00、M01 | S |
| M03 | 模型工厂与最小闭环 | M01 | M |
| M04 | 文档入库 | M03 | M |
| M05 | 检索 | M04 | L |
| M06 | 记忆 | M05 | M |
| M07 | LangGraph 编排 | M03、M05、M06 | L |
| M08 | 意图识别与多知识库路由 | M07 | M |
| M09 | MCP 与 Skills | M07 | M |
| M10 | 知识图谱 | M02、M06 | M |
| M11 | 联调与验收 | 全部 | M |

规模按相对工作量估：S ≈ 1–3 天，M ≈ 3–7 天，L ≈ 1–2 周。

里程碑：M00–M05 跑完，是一条从提问到检索回答的可演示链路；M06–M10 补齐功能对等；M11 做验收和横向对比。

## 三、各模块

### M00 仓库骨架与工程基线

**目标**：能跑起来的最小工程，之后所有模块都在这套地基上加。

**交付物**

- 目录结构：`app/{api,core,db,models,schemas,services,agents,rag}`、`tests/{unit,integration}`、`docs/`、`alembic/`
- `pyproject.toml` + `uv.lock`
- pydantic-settings 配置层 + `.env.example`（真实密钥永不入库）
- structlog JSON 日志，带 trace id
- `GET /api/health`：真实探活 PostgreSQL（`SELECT 1`），依赖不可用返回 503
- `docker-compose.yml`：`postgres`（ParadeDB pg18 镜像，自带 pgvector + pg_search）+ `app`
- GitHub Actions：ruff、mypy、pytest
- `LICENSE`、`README.md` 骨架、`.gitignore`

**验收标准**

- `docker compose up` 后 `/api/health` 返回 200，body 里能看到各依赖状态
- 停掉 postgres 容器，`/api/health` 返回 503（不是 200）
- 新机器上 `uv sync && docker compose up` 能起来
- CI 全绿

**分支**：`module/00-skeleton`

### M01 契约冻结

**目标**：把 Java 版的对外行为固化成文件，之后所有实现以它为准。

**交付物**

- `docs/contract/openapi.yaml`：从 Java 版 `/v3/api-docs` 导出后手工整理，补齐字段说明和错误码
- `docs/contract/sse-events.md`：五个事件（start / sources / delta / error / done）、心跳 `: ping` 15s、sources 固定在首个 delta 之前、无 conversationId 时 start 回传新 id
- `docs/contract/parity-checklist.md`：每个端点、每个 SSE 事件一行，带「未开始 / 实现中 / 已验收」状态列

**验收标准**

- 前端 v0.9.9 调用到的每个接口，都能在 openapi.yaml 里找到对应定义
- checklist 覆盖全部端点和事件，没有"漏了但没人发现"的
- 任何人拿 checklist 都能判断"这个功能算不算做完"

**分支**：`module/01-contract`

### M02 时间线

**目标**：最简单的垂直切片，验证整套栈能跑通。

**交付物**

- CRUD 接口、分页
- 时间字段统一 UTC，`PUT` 传 null 可清空 `end_time`
- Alembic 第一版迁移

**验收标准**

- 字段语义与 Java 版一致
- 任何接口返回的时间都是 UTC（带时区信息）
- `PUT` 能清空 `end_time`，数据库里确实变成 NULL
- 单测覆盖边界，集成测试走真 PG

**分支**：`module/02-timeline`

### M03 模型工厂与最小闭环

**目标**：一次提问能被回答，SSE 格式正确。先不接检索。

**交付物**

- `get_model(role)` 工厂：业务代码只 import `BaseChatModel`
- `/api/chat/ask` SSE 接口
- 会话 id 生成与回传

**验收标准**

- 用 curl 能看到完整事件序列：start → sources → delta × N → done
- sources 一定出现在第一个 delta 之前
- 15 秒一次心跳，长时间生成时连接不断
- 模型或网络异常时发 error 事件而不是直接断开
- 无 conversationId 时，start 事件里带回新建的 id

**分支**：`module/03-chat-loop`

### M04 文档入库

**目标**：上传的文件能解析、切块、入库、算出向量。

**交付物**

- 上传接口 + 解析（PDF / Word / Markdown / 纯文本）
- 切块策略（按段落 + 长度上限 + 重叠）
- 任务状态表 + 后台协程，状态：待处理 / 处理中 / 成功 / 失败
- 失败可重试

**验收标准**

- PDF、Word、md 三种格式都能入库并在库里查到 chunk
- 解析失败的记录状态可见，能手动重试
- 服务重启后，未完成的任务能继续跑
- 同一文件重复上传有明确行为（覆盖或拒绝，写进文档）

**分支**：`module/04-ingest`

### M05 检索

**目标**：一条 query 同时走向量和关键词两路，融合、重排、过闸。

**交付物**

- pgvector HNSW 召回
- pg_search BM25 召回（jieba 分词，`key_field` 建索引）
- RRF 融合（k=60，一条 SQL 完成）
- rerank（API 版）
- 证据闸门：低于阈值判定为"无依据"，不编答案
- 过滤条件（知识库 / 时间范围 / 记忆状态）在两路都生效

**验收标准**

- 两路召回可以分别开关，便于对照
- RRF 分数与手算结果一致（用固定测试集验证）
- 强过滤条件下召回数量仍然满足 top-k
- 阈值、k、召回条数都可配置
- 检索链路有耗时日志

**分支**：`module/05-retrieval`

### M06 记忆

**目标**：记忆三态流转可用。

**交付物**

- 三张表（记忆主体、来源、关联）
- 三态：PENDING（待确认，带 proposedType）/ FACT / INFERRED
- `confirm` / `reject` 接口
- 回答后的异步抽取（`extractAsync` 对应物）

**验收标准**

- PENDING 可以转 FACT 或 INFERRED，reject 后不再出现
- 只有 FACT 和 INFERRED 进检索，PENDING 不进
- confirm 之后立即能被检索到
- 抽取失败不影响主回答链路

**分支**：`module/06-memory`

### M07 LangGraph 编排

**目标**：研究 Agent → 回答 Agent 两段式，用 StateGraph 显式表达。

**交付物**

- StateGraph：research 节点（ReAct + 工具循环）→ answer 节点
- 状态 schema 与 reducer 明确写进文档
- SSE 桥接：图执行过程中的增量转成事件
- 失败回落：任一节点异常不影响已经产出的内容

**验收标准**

- 图能导出成 mermaid 并放进文档
- 一次提问能产出 sources 和 delta，事件顺序与契约一致
- 工具调用有日志（工具名、入参、耗时、结果摘要）
- 任一节点抛异常，前端收到 error 事件而不是挂住
- 递归步数上限可配置，超限有明确提示

**分支**：`module/07-orchestration`

### M08 意图识别与多知识库路由

**目标**：不同问题命中不同知识库。

**交付物**

- 意图分类
- 知识库选择（用工具选择的方式，不是让模型直接输出路由字符串）

**验收标准**

- 针对每个知识库设计的问题，能命中对应库
- 无法判定时回落到全库检索，不报错
- 路由结果可观测（日志里能看到选了哪个库、为什么）

**分支**：`module/08-routing`

### M09 MCP 与 Skills

**目标**：对外暴露 MCP，对内支持 skill 热加载。

**交付物**

- `/mcp` stateless server
- `skills/` 目录扫描 + 热加载

**验收标准**

- 外部 MCP 客户端能列出工具并成功调用
- 往 `skills/` 放一个新目录，不重启服务就能生效
- 损坏的 skill 文件不会拖垮整个服务，有明确错误日志

**分支**：`module/09-mcp-skills`

### M10 知识图谱

**目标**：图的两张表和 1 跳查询。

**交付物**

- `graph_node` / `graph_edge` 两张普通表
- 实体与关系抽取
- 1 跳查询接口

**验收标准**

- 图谱页面用到的接口与契约一致
- 1 跳查询结果正确（用固定样本验证）
- 抽取失败的节点不影响其他数据

**分支**：`module/10-graph`

### M11 联调与验收

**目标**：前端零改动，全功能可用，并且能拿数据和 Java 版对比。

**交付物**

- 前端 v0.9.9 直接对接，不做任何改动
- 评测集在 Python 版上跑通
- 与 Java 版的横向对比报告（`docs/report/parity.md`）
- README 更新：一句话定位、截图、快速开始

**验收标准**

- 六个页面（聊天 / 时间线 / 文档 / 记忆 / 图谱 / 状态）全部功能可用
- 评测指标不低于 Java 版，或差距有明确解释
- `docker compose up` 一条命令起完整系统
- README 与代码一致，没有指向已删除内容的链接

**分支**：`module/11-acceptance`

## 四、风险清单

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| `pg_search` 镜像或版本不匹配 | 起不来 / 索引建不了 | 锁定 ParadeDB 镜像 tag，CI 里跑一次建索引冒烟 |
| 换分词器或词典后旧索引失效 | 召回骤降且难排查 | 写进运维手册：改分词必须重建索引 |
| `pg_search` 是 AGPL-3.0 | 将来闭源分发受限 | 现在标注，届时再评估 |
| jieba 不支持自定义词典 | 项目名、人名召回不到 | 提前验证；不行就退 pg_jieba 或应用层预处理 |
| SSE 事件顺序被前端硬依赖 | 前端白屏或丢字 | 契约里写死顺序，M03 和 M07 都要按契约验收 |
| 时区处理不一致 | 时间线错位 | 统一 UTC，接口层做一次断言 |
| 契约不完整 | "零改动"落空，前端被迫改 | M01 用前端代码反查，逐接口对照 |

## 五、下一步

1. 在 GitHub 建 `RecordAndGrow-py`，把本目录的 `docs/` 搬进去
2. 建 `module/00-skeleton` 分支，按 M00 交付物搭骨架
3. M00 验收通过后，再把 Java 版的 `v1.0` tag 打上、README 加上互链
