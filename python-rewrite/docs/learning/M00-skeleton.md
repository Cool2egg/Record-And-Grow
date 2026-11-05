# M00 仓库骨架与工程基线

- 目录按 `app/{api,core,db,models,schemas,services,agents,rag}` 划分；`agents` 最终落在 `app/agent`（编排、工具、提示词）。
- 依赖用 uv 管理，`pyproject.toml` 里只有 `[project]` 与 `[project.optional-dependencies].dev` 两处，避免两套 dev 依赖清单漂移。
- Windows 上 `zoneinfo` 缺少系统时区库，必须显式依赖 `tzdata`；Docker slim 镜像同理。
- 配置层用 pydantic-settings，同时支持平铺环境变量（`DEEPSEEK_API_KEY`）与嵌套写法，部署时不用改习惯。
- 健康检查真实执行 `SELECT 1` 并检查 pgvector，数据库不可用返回 503 而不是 200。
- 踩坑：uv 构建需要 `README.md` 存在；`hatchling` 缺它直接构建失败。
