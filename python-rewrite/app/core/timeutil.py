"""时间工具：对外时间统一 UTC（``Z``），内部语义保持瞬时一致。"""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from pydantic import BaseModel, field_serializer


def to_utc(dt: datetime | None) -> datetime | None:
    """把任意时区感知时间换成 UTC 偏移，保持瞬时不变。naive 视为 UTC。"""
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def now_utc() -> datetime:
    return datetime.now(UTC)


class UtcModel(BaseModel):
    """带 ``field_serializer`` 的基类：所有 datetime 字段序列化成 UTC ISO-8601。

    用 ``Any`` 兜底的 serializer 会对每个 datetime 字段生效，避免逐个字段声明。
    """

    @field_serializer("*", when_used="json", check_fields=False)
    def _serialize_datetime(self, value: Any, _info: Any) -> Any:
        if isinstance(value, datetime):
            return to_utc(value)
        return value
