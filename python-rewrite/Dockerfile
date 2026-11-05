FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy

RUN pip install --no-cache-dir uv

# 锁文件一起拷：构建可复现，升级依赖必须显式改 uv.lock
COPY pyproject.toml uv.lock ./
RUN uv sync --no-dev --locked

COPY app ./app
COPY alembic ./alembic
COPY alembic.ini ./

ENV PATH="/app/.venv/bin:$PATH"

RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /app/data /app/skills \
    && chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

# 迁移失败就不启动；uvicorn 单进程，SSE 场景够用
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8080"]
