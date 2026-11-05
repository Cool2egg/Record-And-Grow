"""集成测试：需要 Docker 或一个可用的 PostgreSQL。默认跳过。

两种跑法：
- CI/有 Docker：`RUN_INTEGRATION=1 pytest`，用 testcontainers 起 pgvector 容器；
- 本机已有库：设置 `INTEGRATION_DATABASE_URL=postgresql+asyncpg://...` 直接用。

验证信封结构、时间线 CRUD 与 UTC 时间。
"""
from __future__ import annotations

import asyncio
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("RUN_INTEGRATION") != "1",
    reason="设置 RUN_INTEGRATION=1 启用；或提供 INTEGRATION_DATABASE_URL",
)


@pytest.fixture(scope="module")
def postgres_url():
    external = os.environ.get("INTEGRATION_DATABASE_URL")
    if external:
        os.environ["DATABASE_URL"] = external
        yield external
        return

    from testcontainers.postgres import PostgresContainer

    with PostgresContainer("pgvector/pgvector:pg16", driver="asyncpg") as container:
        url = container.get_connection_url()
        os.environ["DATABASE_URL"] = url
        yield url


def test_timeline_crud(postgres_url):
    from alembic.config import Config

    from app.core.config import get_settings

    get_settings.cache_clear()
    cfg = Config("alembic.ini")
    asyncio.run(_upgrade(cfg))

    from httpx import ASGITransport, AsyncClient

    from app.main import app

    async def run() -> None:
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            created = await client.post(
                "/api/timeline",
                json={"content": "集成测试记录", "startTime": "2026-11-03T15:30:00+08:00", "endTime": None},
            )
            assert created.status_code == 200
            body = created.json()
            assert body["code"] == 0
            entry_id = body["data"]["id"]
            assert body["data"]["startTime"].startswith("2026-11-03T07:30:00")

            listed = await client.get("/api/timeline?page=1&size=100")
            items = listed.json()["data"]["items"]
            assert any(item["id"] == entry_id for item in items)

            updated = await client.put(
                f"/api/timeline/{entry_id}",
                json={"content": "集成测试记录", "startTime": "2026-11-03T15:30:00+08:00", "endTime": None},
            )
            assert updated.json()["data"]["endTime"] is None

            deleted = await client.delete(f"/api/timeline/{entry_id}")
            assert deleted.json()["code"] == 0

    asyncio.run(run())


async def _upgrade(cfg) -> None:
    from alembic import command

    await asyncio.to_thread(command.upgrade, cfg, "head")
