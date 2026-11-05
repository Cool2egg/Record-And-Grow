"""记忆。"""
from __future__ import annotations

from datetime import datetime
from typing import Literal

from app.schemas.base import BaseSchema


class MemoryEvidence(BaseSchema):
    type: Literal["timeline", "document", "conversation"]
    id: int | None = None
    excerpt: str


class Memory(BaseSchema):
    id: int
    type: str
    proposed_type: str | None = None
    content: str
    evidence: list[MemoryEvidence] = []
    source: str
    confidence: float | None = None
    conversation_id: int | None = None
    confirmed_at: datetime | None = None
    created_at: datetime
    updated_at: datetime


class MemoryConfirmRequest(BaseSchema):
    content: str | None = None
    type: Literal["FACT", "INFERRED"] | None = None


class MemoryCreateRequest(BaseSchema):
    content: str
