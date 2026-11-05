"""时间线服务。"""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessException, ErrorCodes
from app.core.response import PageResult
from app.models.timeline import TimelineEntry
from app.schemas.timeline import TimelineEntry as TimelineEntrySchema
from app.schemas.timeline import TimelineEntryRequest

MAX_PAGE_SIZE = 100


def parse_iso(value: str | None, name: str) -> datetime | None:
    if value is None or not value.strip():
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError as exc:
        raise BusinessException(
            ErrorCodes.BAD_REQUEST,
            f"{name} 时间格式错误，应为 ISO-8601（如 2026-10-14T00:00:00+08:00）",
        ) from exc


def to_schema(entry: TimelineEntry) -> TimelineEntrySchema:
    return TimelineEntrySchema.model_validate(entry)


async def create(session: AsyncSession, request: TimelineEntryRequest) -> TimelineEntrySchema:
    entry = TimelineEntry(
        content=request.content,
        start_time=request.start_time,
        end_time=request.end_time,
    )
    session.add(entry)
    await session.commit()
    await session.refresh(entry)
    return to_schema(entry)


async def get(session: AsyncSession, entry_id: int) -> TimelineEntrySchema:
    entry = await _must_exist(session, entry_id)
    return to_schema(entry)


async def update(session: AsyncSession, entry_id: int, request: TimelineEntryRequest) -> TimelineEntrySchema:
    entry = await _must_exist(session, entry_id)
    entry.content = request.content
    entry.start_time = request.start_time
    entry.end_time = request.end_time  # None 即清空
    await session.commit()
    await session.refresh(entry)
    return to_schema(entry)


async def delete(session: AsyncSession, entry_id: int) -> None:
    entry = await _must_exist(session, entry_id)
    entry.deleted = True
    await session.commit()


async def list_entries(
    session: AsyncSession,
    start: datetime | None,
    end: datetime | None,
    page: int,
    size: int,
) -> PageResult[TimelineEntrySchema]:
    safe_page = max(1, page)
    safe_size = max(1, min(size, MAX_PAGE_SIZE))
    conditions = [TimelineEntry.deleted.is_(False)]
    if start is not None:
        conditions.append(TimelineEntry.start_time >= start)
    if end is not None:
        conditions.append(TimelineEntry.start_time < end)

    total = int(
        (
            await session.execute(
                select(func.count()).select_from(
                    select(TimelineEntry.id).where(*conditions).subquery()
                )
            )
        ).scalar_one()
    )
    rows = (
        (
            await session.execute(
                select(TimelineEntry)
                .where(*conditions)
                .order_by(TimelineEntry.start_time.desc(), TimelineEntry.id.desc())
                .offset((safe_page - 1) * safe_size)
                .limit(safe_size)
            )
        )
        .scalars()
        .all()
    )
    return PageResult[TimelineEntrySchema](
        items=[to_schema(row) for row in rows],
        total=total,
        page=safe_page,
        size=safe_size,
    )


async def _must_exist(session: AsyncSession, entry_id: int) -> TimelineEntry:
    entry = await session.get(TimelineEntry, entry_id)
    if entry is None or entry.deleted:
        raise BusinessException(ErrorCodes.NOT_FOUND, "时间线记录不存在")
    return entry
