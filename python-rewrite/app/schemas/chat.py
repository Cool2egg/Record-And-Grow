"""对话请求与 SSE 事件载荷。"""
from __future__ import annotations

from datetime import datetime

from pydantic import Field

from app.schemas.base import BaseSchema


class ChatAskRequest(BaseSchema):
    question: str = Field(min_length=1)
    conversation_id: int | None = None
    max_tokens: int | None = Field(default=None, ge=64, le=4096)
    temperature: float | None = Field(default=None, ge=0.0, le=2.0)


class ChatUsage(BaseSchema):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


class SourceItem(BaseSchema):
    ref: str
    type: str
    id: int | None = None
    start_time: datetime | None = None
    end_time: datetime | None = None
    excerpt: str
    chunk_id: int | None = None
    title: str | None = None
    heading: str | None = None
    score: float | None = None
    memory_type: str | None = None
    document_id: int | None = None


class RetrievalMeta(BaseSchema):
    window_start: datetime | None = None
    window_end: datetime | None = None
    time_basis: str
    clamped: bool
    matched: int
    retrieved: int
    included: int
    limit_truncated: bool
    budget_truncated: bool
    keywords: list[str] = []
    keyword_fallback: bool
    document_searched: bool
    documents_retrieved: int
    document_included: int
    total_included: int
    documents_degraded: bool
    degrade_reason: str | None = None
    memory_included: int = 0
    graph_included: int = 0


class Sources(BaseSchema):
    items: list[SourceItem] = []
    retrieval: RetrievalMeta


class Step(BaseSchema):
    seq: int
    tool: str
    query: str
    hits: int
    ok: bool
    ms: int
    error: str | None = None


class StreamStart(BaseSchema):
    request_id: str
    conversation_id: int | None = None


class StreamDone(BaseSchema):
    finish_reason: str
    usage: ChatUsage | None = None


class StreamError(BaseSchema):
    code: int
    error_type: str
    message: str
    partial: bool
    request_id: str


class Delta(BaseSchema):
    content: str


class ChatAnswerResponse(BaseSchema):
    request_id: str
    content: str
    sources: list[SourceItem]
    retrieval: RetrievalMeta
    finish_reason: str
    usage: ChatUsage
