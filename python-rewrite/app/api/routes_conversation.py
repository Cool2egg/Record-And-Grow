"""会话接口。"""
from __future__ import annotations

from fastapi import APIRouter, Body, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.response import ApiResponse
from app.db.session import get_session
from app.schemas.conversation import ConversationCreateRequest
from app.services import conversation as conversation_service

router = APIRouter(prefix="/api/conversations", tags=["conversation"])


@router.get("")
async def list_conversations(
    page: int = Query(default=1),
    size: int = Query(default=20),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(await conversation_service.list_conversations(session, page, size))


@router.post("")
async def create(
    request: ConversationCreateRequest | None = Body(default=None),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(
        await conversation_service.create(session, request.title if request else None)
    )


@router.get("/{conversation_id}")
async def get(conversation_id: int, session: AsyncSession = Depends(get_session)):
    return ApiResponse.success(await conversation_service.get(session, conversation_id))


@router.get("/{conversation_id}/messages")
async def messages(
    conversation_id: int,
    page: int = Query(default=1),
    size: int = Query(default=50),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(
        await conversation_service.messages(session, conversation_id, page, size)
    )


@router.delete("/{conversation_id}")
async def delete(conversation_id: int, session: AsyncSession = Depends(get_session)):
    await conversation_service.delete(session, conversation_id)
    return ApiResponse.success()
