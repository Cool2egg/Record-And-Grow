"""会话与消息服务。"""
from __future__ import annotations

import re

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.errors import BusinessException, ErrorCodes
from app.core.logging import get_logger
from app.core.response import PageResult
from app.models.memory import Conversation, Message
from app.schemas.conversation import ChatMessage
from app.schemas.conversation import Conversation as ConversationSchema

log = get_logger(__name__)

MAX_PAGE_SIZE = 100
TITLE_CHARS = 30
DEFAULT_TITLE = "新对话"


def _title_from(question: str | None) -> str:
    if not question or not question.strip():
        return DEFAULT_TITLE
    trimmed = re.sub(r"\s+", " ", question.strip())
    return trimmed if len(trimmed) <= TITLE_CHARS else trimmed[:TITLE_CHARS] + "…"


def to_schema(conversation: Conversation) -> ConversationSchema:
    return ConversationSchema.model_validate(conversation)


async def create(session: AsyncSession, title: str | None) -> ConversationSchema:
    conversation = Conversation(
        title=title.strip() if title and title.strip() else DEFAULT_TITLE,
        summary_upto_message_id=0,
        message_count=0,
    )
    session.add(conversation)
    await session.commit()
    await session.refresh(conversation)
    return to_schema(conversation)


async def get_or_create(session: AsyncSession, conversation_id: int | None, first_question: str) -> Conversation:
    if conversation_id is not None:
        return await must_exist(session, conversation_id)
    conversation = Conversation(
        title=_title_from(first_question),
        summary_upto_message_id=0,
        message_count=0,
    )
    session.add(conversation)
    await session.commit()
    await session.refresh(conversation)
    return conversation


async def get(session: AsyncSession, conversation_id: int) -> ConversationSchema:
    return to_schema(await must_exist(session, conversation_id))


async def list_conversations(session: AsyncSession, page: int, size: int) -> PageResult[ConversationSchema]:
    safe_page = max(1, page)
    safe_size = max(1, min(size, MAX_PAGE_SIZE))
    total = int(
        (
            await session.execute(
                select(func.count()).select_from(
                    select(Conversation.id).where(Conversation.deleted.is_(False)).subquery()
                )
            )
        ).scalar_one()
    )
    rows = (
        (
            await session.execute(
                select(Conversation)
                .where(Conversation.deleted.is_(False))
                .order_by(Conversation.updated_at.desc(), Conversation.id.desc())
                .offset((safe_page - 1) * safe_size)
                .limit(safe_size)
            )
        )
        .scalars()
        .all()
    )
    return PageResult[ConversationSchema](
        items=[to_schema(row) for row in rows],
        total=total,
        page=safe_page,
        size=safe_size,
    )


async def messages(
    session: AsyncSession, conversation_id: int, page: int, size: int
) -> PageResult[ChatMessage]:
    await must_exist(session, conversation_id)
    safe_page = max(1, page)
    safe_size = max(1, min(size, MAX_PAGE_SIZE))
    total = int(
        (
            await session.execute(
                select(func.count()).select_from(
                    select(Message.id)
                    .where(Message.conversation_id == conversation_id)
                    .subquery()
                )
            )
        ).scalar_one()
    )
    rows = (
        (
            await session.execute(
                select(Message)
                .where(Message.conversation_id == conversation_id)
                .order_by(Message.id.asc())
                .offset((safe_page - 1) * safe_size)
                .limit(safe_size)
            )
        )
        .scalars()
        .all()
    )
    return PageResult[ChatMessage](
        items=[ChatMessage.model_validate(row) for row in rows],
        total=total,
        page=safe_page,
        size=safe_size,
    )


async def append(
    session: AsyncSession,
    conversation_id: int,
    role: str,
    content: str,
    sources_json: str | None = None,
) -> Message:
    conversation = await must_exist(session, conversation_id)
    message = Message(
        conversation_id=conversation_id,
        role=role,
        content=content,
        sources=sources_json,
        token_count=0,
    )
    session.add(message)
    conversation.message_count = (conversation.message_count or 0) + 1
    await session.commit()
    await session.refresh(message)
    return message


async def ensure_title(session: AsyncSession, conversation_id: int, question: str) -> None:
    conversation = await must_exist(session, conversation_id)
    if conversation.title == DEFAULT_TITLE:
        conversation.title = _title_from(question)
        await session.commit()


async def history(session: AsyncSession, conversation_id: int) -> tuple[str | None, list[dict[str, str]]]:
    """摘要 + 水位之后的最近消息，按字符预算从新到旧截断。"""
    conversation = await must_exist(session, conversation_id)
    upto = conversation.summary_upto_message_id or 0
    max_messages = max(2, settings.memory.history_max_messages)
    recent = (
        (
            await session.execute(
                select(Message)
                .where(Message.conversation_id == conversation_id, Message.id > upto)
                .order_by(Message.id.desc())
                .limit(max_messages)
            )
        )
        .scalars()
        .all()
    )
    recent = list(reversed(recent))

    budget = max(200, settings.memory.history_max_chars)
    kept: list[dict[str, str]] = []
    for message in reversed(recent):
        length = len(message.content or "")
        if kept and budget - length < 0:
            break
        budget -= length
        role = "user" if message.role == "USER" else "assistant"
        kept.insert(0, {"role": role, "content": message.content or ""})
    return conversation.summary, kept


async def delete(session: AsyncSession, conversation_id: int) -> None:
    conversation = await must_exist(session, conversation_id)
    conversation.deleted = True
    await session.commit()


async def must_exist(session: AsyncSession, conversation_id: int) -> Conversation:
    conversation = await session.get(Conversation, conversation_id)
    if conversation is None or conversation.deleted:
        raise BusinessException(ErrorCodes.NOT_FOUND, "会话不存在")
    return conversation
