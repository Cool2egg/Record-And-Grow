"""会话滚动摘要：消息数超过阈值时，把较早的消息压成摘要。"""
from __future__ import annotations

from sqlalchemy import select

from app.core.config import settings
from app.core.logging import get_logger
from app.db.session import SessionLocal
from app.models.memory import Conversation, Message
from app.rag.models import get_model

log = get_logger(__name__)

_SUMMARY_PROMPT = """把下面的对话压缩成不超过 {max_chars} 字的摘要，保留用户的目标、偏好、结论与未解决的问题。
只输出摘要正文，不要任何前缀。

{dialogue}
"""


async def maybe_summarize(conversation_id: int) -> None:
    if not settings.memory.enabled:
        return
    client = get_model("chat")
    if not client.is_configured():
        return

    async with SessionLocal() as session:
        conversation = await session.get(Conversation, conversation_id)
        if conversation is None or (conversation.message_count or 0) < settings.memory.summary_threshold:
            return

        keep = max(2, settings.memory.summary_keep)
        rows = (
            (
                await session.execute(
                    select(Message)
                    .where(Message.conversation_id == conversation_id)
                    .order_by(Message.id.asc())
                )
            )
            .scalars()
            .all()
        )
        if len(rows) <= keep:
            return
        older = rows[:-keep]
        if conversation.summary_upto_message_id and older and older[-1].id <= conversation.summary_upto_message_id:
            return

        dialogue_lines: list[str] = []
        if conversation.summary:
            dialogue_lines.append(f"（已有摘要）{conversation.summary}")
        for message in older:
            role = "用户" if message.role == "USER" else "助手"
            dialogue_lines.append(f"{role}：{message.content[:300]}")
        dialogue = "\n".join(dialogue_lines)[-6000:]

    try:
        completion = await client.complete(
            [
                {
                    "role": "user",
                    "content": _SUMMARY_PROMPT.format(
                        max_chars=settings.memory.summary_max_chars, dialogue=dialogue
                    ),
                }
            ],
            max_tokens=512,
            temperature=0.2,
            timeout_s=settings.memory.async_timeout_s,
        )
        summary = (completion.content or "").strip()
    except Exception as exc:  # noqa: BLE001 - 摘要失败不影响主链路
        log.warning("summary_failed", conversation_id=conversation_id, error=str(exc))
        return
    if not summary:
        return

    async with SessionLocal() as session:
        conversation = await session.get(Conversation, conversation_id)
        if conversation is None:
            return
        conversation.summary = summary[: settings.memory.summary_max_chars]
        conversation.summary_upto_message_id = older[-1].id
        await session.commit()
