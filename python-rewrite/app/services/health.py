"""健康检查：真实探活数据库与 pgvector。"""
from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessException, ErrorCodes
from app.schemas.system import HealthStatus


async def check(session: AsyncSession) -> HealthStatus:
    try:
        one = (await session.execute(text("SELECT 1"))).scalar_one()
    except Exception as exc:  # noqa: BLE001
        raise BusinessException(ErrorCodes.DB_UNAVAILABLE, "数据库不可用") from exc
    if one != 1:
        raise BusinessException(ErrorCodes.DB_UNAVAILABLE, "数据库探活失败")

    try:
        count = (
            await session.execute(
                text("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'")
            )
        ).scalar_one()
    except Exception as exc:  # noqa: BLE001
        raise BusinessException(ErrorCodes.DB_UNAVAILABLE, "数据库不可用") from exc
    if not count:
        raise BusinessException(ErrorCodes.DB_UNAVAILABLE, "pgvector 扩展未安装")
    return HealthStatus(status="UP", database="UP", vector_extension=True)
