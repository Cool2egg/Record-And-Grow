"""时间线接口。"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.response import ApiResponse
from app.db.session import get_session
from app.schemas.timeline import TimelineEntryRequest
from app.services import timeline as timeline_service

router = APIRouter(prefix="/api/timeline", tags=["timeline"])


@router.post("")
async def create(request: TimelineEntryRequest, session: AsyncSession = Depends(get_session)):
    return ApiResponse.success(await timeline_service.create(session, request))


@router.get("/{entry_id}")
async def get(entry_id: int, session: AsyncSession = Depends(get_session)):
    return ApiResponse.success(await timeline_service.get(session, entry_id))


@router.get("")
async def list_entries(
    start: str | None = Query(default=None),
    end: str | None = Query(default=None),
    page: int = Query(default=1),
    size: int = Query(default=20),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(
        await timeline_service.list_entries(
            session,
            timeline_service.parse_iso(start, "start"),
            timeline_service.parse_iso(end, "end"),
            page,
            size,
        )
    )


@router.put("/{entry_id}")
async def update(
    entry_id: int, request: TimelineEntryRequest, session: AsyncSession = Depends(get_session)
):
    return ApiResponse.success(await timeline_service.update(session, entry_id, request))


@router.delete("/{entry_id}")
async def delete(entry_id: int, session: AsyncSession = Depends(get_session)):
    await timeline_service.delete(session, entry_id)
    return ApiResponse.success()
