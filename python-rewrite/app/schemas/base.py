"""Schema 基类：snake_case 字段 → camelCase JSON，datetime 统一序列化 UTC。"""
from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, field_serializer

from app.core.timeutil import to_utc


def to_camel(value: str) -> str:
    head, *rest = value.split("_")
    return head + "".join(p.capitalize() for p in rest)


class BaseSchema(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        from_attributes=True,
    )

    @field_serializer("*", when_used="json", check_fields=False)
    def _serialize_datetime(self, value: Any, _info: Any) -> Any:
        if isinstance(value, datetime):
            return to_utc(value).isoformat()
        return value
