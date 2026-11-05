"""长期记忆：三态流转 + 检索 + 回答后的异步抽取。"""
from __future__ import annotations

import json
import re
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.errors import BusinessException, ErrorCodes
from app.core.logging import get_logger
from app.core.response import PageResult
from app.models.memory import Memory
from app.models.memory import Message as MessageModel
from app.rag.models import get_model
from app.schemas.memory import Memory as MemorySchema
from app.schemas.memory import MemoryEvidence

log = get_logger(__name__)

MAX_PAGE_SIZE = 100
MAX_CONTENT_CHARS = 500
TRUSTED = ("FACT", "INFERRED")


def normalize_memory(content: str) -> str:
    return re.sub(r"\s+", " ", (content or "").strip().lower())


def to_schema(memory: Memory) -> MemorySchema:
    evidence: list[MemoryEvidence] = []
    if memory.evidence:
        try:
            parsed = json.loads(memory.evidence)
            if isinstance(parsed, list):
                evidence = [MemoryEvidence.model_validate(item) for item in parsed]
        except (ValueError, TypeError):
            evidence = []
    return MemorySchema(
        id=memory.id,
        type=memory.type,
        proposed_type=memory.proposed_type,
        content=memory.content,
        evidence=evidence,
        source=memory.source,
        confidence=memory.confidence,
        conversation_id=memory.conversation_id,
        confirmed_at=memory.confirmed_at,
        created_at=memory.created_at,
        updated_at=memory.updated_at,
    )


async def list_memories(
    session: AsyncSession, memory_type: str | None, page: int, size: int
) -> PageResult[MemorySchema]:
    safe_page = max(1, page)
    safe_size = max(1, min(size, MAX_PAGE_SIZE))
    conditions = [Memory.deleted.is_(False)]
    if memory_type:
        parsed = parse_type(memory_type)
        if parsed is None:
            raise BusinessException(ErrorCodes.BAD_REQUEST, "type 只能是 PENDING / FACT / INFERRED")
        conditions.append(Memory.type == parsed)

    total = int(
        (
            await session.execute(
                select(func.count()).select_from(select(Memory.id).where(*conditions).subquery())
            )
        ).scalar_one()
    )
    rows = (
        (
            await session.execute(
                select(Memory)
                .where(*conditions)
                .order_by(Memory.updated_at.desc(), Memory.id.desc())
                .offset((safe_page - 1) * safe_size)
                .limit(safe_size)
            )
        )
        .scalars()
        .all()
    )
    return PageResult[MemorySchema](
        items=[to_schema(row) for row in rows],
        total=total,
        page=safe_page,
        size=safe_size,
    )


async def pending(session: AsyncSession, limit: int) -> list[MemorySchema]:
    safe_limit = max(1, min(limit, 100))
    rows = (
        (
            await session.execute(
                select(Memory)
                .where(Memory.deleted.is_(False), Memory.type == "PENDING")
                .order_by(Memory.created_at.desc(), Memory.id.desc())
                .limit(safe_limit)
            )
        )
        .scalars()
        .all()
    )
    return [to_schema(row) for row in rows]


async def search(session: AsyncSession, query: str, limit: int) -> list[MemorySchema]:
    if not query or not query.strip():
        return []
    safe_limit = max(1, min(limit, 50))
    pattern = f"%{query.strip()}%"
    rows = (
        (
            await session.execute(
                select(Memory)
                .where(
                    Memory.deleted.is_(False),
                    Memory.type.in_(TRUSTED),
                    Memory.content.ilike(pattern),
                )
                .order_by(Memory.updated_at.desc(), Memory.id.desc())
                .limit(safe_limit)
            )
        )
        .scalars()
        .all()
    )
    return [to_schema(row) for row in rows]


async def create_manual(session: AsyncSession, content: str) -> MemorySchema:
    text = (content or "").strip()
    if not text:
        raise BusinessException(ErrorCodes.BAD_REQUEST, "记忆内容不能为空")
    memory = Memory(
        type="FACT",
        proposed_type=None,
        content=text[:MAX_CONTENT_CHARS],
        normalized=normalize_memory(text)[:MAX_CONTENT_CHARS],
        evidence=json.dumps([], ensure_ascii=False),
        source="USER",
        confidence=1.0,
        confirmed_at=datetime.now(UTC),
    )
    session.add(memory)
    await session.commit()
    await session.refresh(memory)
    return to_schema(memory)


async def confirm(
    session: AsyncSession, memory_id: int, content: str | None, requested_type: str | None
) -> MemorySchema:
    memory = await _must_exist(session, memory_id)
    if memory.type != "PENDING":
        raise BusinessException(ErrorCodes.BAD_REQUEST, "只有待确认的记忆可以确认")
    target = requested_type or memory.proposed_type or "FACT"
    if target not in TRUSTED:
        target = "FACT"
    text = (content or memory.content).strip() or memory.content
    memory.type = target
    memory.proposed_type = None
    memory.content = text[:MAX_CONTENT_CHARS]
    memory.normalized = normalize_memory(text)[:MAX_CONTENT_CHARS]
    memory.confirmed_at = datetime.now(UTC)
    await session.commit()
    await session.refresh(memory)
    return to_schema(memory)


async def reject(session: AsyncSession, memory_id: int) -> None:
    memory = await _must_exist(session, memory_id)
    memory.deleted = True
    await session.commit()


async def delete(session: AsyncSession, memory_id: int) -> None:
    memory = await _must_exist(session, memory_id)
    memory.deleted = True
    await session.commit()


async def add_candidate(
    session: AsyncSession,
    content: str,
    proposed_type: str,
    confidence: float,
    evidence: list[MemoryEvidence],
    source: str,
    conversation_id: int | None,
) -> int | None:
    """写入一条待确认记忆；与已有待确认候选重复时返回 None。"""
    text = (content or "").strip()
    if not text:
        return None
    normalized = normalize_memory(text)[:MAX_CONTENT_CHARS]
    existing = (
        await session.execute(
            select(Memory.id).where(
                Memory.deleted.is_(False),
                Memory.type == "PENDING",
                Memory.normalized == normalized,
            )
        )
    ).scalar_one_or_none()
    if existing is not None:
        return None

    memory = Memory(
        type="PENDING",
        proposed_type=proposed_type if proposed_type in TRUSTED else "INFERRED",
        content=text[:MAX_CONTENT_CHARS],
        normalized=normalized,
        evidence=json.dumps(
            [item.model_dump(by_alias=False) for item in evidence], ensure_ascii=False
        ),
        source=source,
        confidence=confidence,
        conversation_id=conversation_id,
    )
    session.add(memory)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        return None
    await session.refresh(memory)
    return memory.id


EXTRACTION_PROMPT = """从下面这段对话中提炼用户长期有效的事实或偏好，输出 JSON 数组，最多 {max_candidates} 条。
每条形如 {{"content": "一条记忆，60 字以内，第三人称", "type": "FACT 或 INFERRED"}}。
只提炼长期有效的信息（身份、偏好、目标、习惯），不要一次性事件。没有就输出 []。
只输出 JSON，不要任何解释。

用户：{question}
助手：{answer}
"""


async def extract_candidates(
    question: str, answer: str, conversation_id: int | None
) -> None:
    """回答后的异步抽取；失败只记日志，不影响主链路。"""
    if not settings.memory.extraction_enabled or not settings.memory.enabled:
        return
    client = get_model("chat")
    if not client.is_configured():
        return
    try:
        completion = await client.complete(
            [
                {
                    "role": "user",
                    "content": EXTRACTION_PROMPT.format(
                        max_candidates=settings.memory.extraction_max_candidates,
                        question=question[:1500],
                        answer=answer[:3000],
                    ),
                }
            ],
            max_tokens=512,
            temperature=0.0,
            timeout_s=settings.memory.async_timeout_s,
        )
        candidates = _parse_candidates(completion.content)
    except Exception as exc:  # noqa: BLE001 - 抽取失败不影响主回答
        log.warning("memory_extraction_failed", error=str(exc))
        return
    if not candidates:
        return

    from app.db.session import SessionLocal

    async with SessionLocal() as session:
        for content, proposed_type in candidates[: settings.memory.extraction_max_candidates]:
            await add_candidate(
                session,
                content=content,
                proposed_type=proposed_type,
                confidence=0.7,
                evidence=[
                    MemoryEvidence(
                        type="conversation",
                        id=conversation_id,
                        excerpt=content[:80],
                    )
                ],
                source="AI_EXTRACTED",
                conversation_id=conversation_id,
            )


def _parse_candidates(raw: str) -> list[tuple[str, str]]:
    if not raw:
        return []
    text = raw.strip()
    start = text.find("[")
    end = text.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        parsed = json.loads(text[start:end + 1])
    except ValueError:
        return []
    result: list[tuple[str, str]] = []
    if not isinstance(parsed, list):
        return result
    for item in parsed:
        if not isinstance(item, dict):
            continue
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        proposed = str(item.get("type") or "INFERRED").upper()
        if proposed not in TRUSTED:
            proposed = "INFERRED"
        result.append((content[:MAX_CONTENT_CHARS], proposed))
    return result


def parse_type(raw: str | None) -> str | None:
    if raw is None:
        return None
    normalized = raw.strip().upper()
    return normalized if normalized in ("PENDING", "FACT", "INFERRED") else None


async def _must_exist(session: AsyncSession, memory_id: int) -> Memory:
    memory = await session.get(Memory, memory_id)
    if memory is None or memory.deleted:
        raise BusinessException(ErrorCodes.NOT_FOUND, "记忆不存在")
    return memory


async def message_count_for(session: AsyncSession, conversation_id: int) -> int:
    return int(
        (
            await session.execute(
                select(func.count()).select_from(
                    select(MessageModel.id)
                    .where(MessageModel.conversation_id == conversation_id)
                    .subquery()
                )
            )
        ).scalar_one()
    )
