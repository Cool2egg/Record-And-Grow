"""记忆接口。"""
from __future__ import annotations

from fastapi import APIRouter, Body, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.response import ApiResponse
from app.db.session import get_session
from app.schemas.memory import MemoryConfirmRequest, MemoryCreateRequest
from app.services import memory as memory_service

router = APIRouter(prefix="/api/memories", tags=["memory"])


@router.get("")
async def list_memories(
    type: str | None = Query(default=None),
    page: int = Query(default=1),
    size: int = Query(default=20),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(await memory_service.list_memories(session, type, page, size))


@router.get("/pending")
async def pending(
    limit: int = Query(default=20), session: AsyncSession = Depends(get_session)
):
    return ApiResponse.success(await memory_service.pending(session, limit))


@router.get("/search")
async def search(
    q: str = Query(), limit: int = Query(default=5), session: AsyncSession = Depends(get_session)
):
    return ApiResponse.success(await memory_service.search(session, q, limit))


@router.post("")
async def create(request: MemoryCreateRequest, session: AsyncSession = Depends(get_session)):
    return ApiResponse.success(await memory_service.create_manual(session, request.content))


@router.post("/{memory_id}/confirm")
async def confirm(
    memory_id: int,
    request: MemoryConfirmRequest | None = Body(default=None),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(
        await memory_service.confirm(
            session,
            memory_id,
            request.content if request else None,
            request.type if request else None,
        )
    )


@router.post("/{memory_id}/reject")
async def reject(memory_id: int, session: AsyncSession = Depends(get_session)):
    await memory_service.reject(session, memory_id)
    return ApiResponse.success()


@router.delete("/{memory_id}")
async def delete(memory_id: int, session: AsyncSession = Depends(get_session)):
    await memory_service.delete(session, memory_id)
    return ApiResponse.success()
