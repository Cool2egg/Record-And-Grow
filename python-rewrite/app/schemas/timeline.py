"""时间线。"""
from __future__ import annotations

from datetime import datetime

from pydantic import Field

from app.schemas.base import BaseSchema


class TimelineEntryRequest(BaseSchema):
    content: str = Field(min_length=1)
    start_time: datetime
    end_time: datetime | None = None


class TimelineEntry(BaseSchema):
    id: int
    content: str
    start_time: datetime
    end_time: datetime | None
    created_at: datetime
    updated_at: datetime
