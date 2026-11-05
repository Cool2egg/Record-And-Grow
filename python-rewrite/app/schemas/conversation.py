"""会话与消息。"""
from __future__ import annotations

from datetime import datetime
from typing import Literal

from app.schemas.base import BaseSchema


class ConversationCreateRequest(BaseSchema):
    title: str | None = None


class Conversation(BaseSchema):
    id: int
    title: str
    summary: str | None = None
    message_count: int = 0
    created_at: datetime
    updated_at: datetime


class ChatMessage(BaseSchema):
    id: int
    role: Literal["USER", "ASSISTANT", "SYSTEM"]
    content: str
    sources: str | None = None
    token_count: int = 0
    created_at: datetime
