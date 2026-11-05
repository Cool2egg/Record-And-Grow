"""文档。"""
from __future__ import annotations

from datetime import datetime

from app.schemas.base import BaseSchema


class KnowledgeDocument(BaseSchema):
    id: int
    title: str
    file_name: str
    mime_type: str
    file_size: int
    status: str
    chunk_count: int | None = None
    char_count: int | None = None
    token_count: int | None = None
    chunk_truncated: bool | None = None
    error_message: str | None = None
    created_at: datetime
    updated_at: datetime


class DocumentUploadResponse(BaseSchema):
    document: KnowledgeDocument
    deduplicated: bool


class DocumentChunk(BaseSchema):
    id: int
    chunk_index: int
    content: str
    heading: str | None = None
    char_start: int | None = None
    char_end: int | None = None
    char_count: int | None = None
    token_count: int | None = None


class ChunkLocator(BaseSchema):
    chunk: DocumentChunk
    page: int
    size: int
    total: int
