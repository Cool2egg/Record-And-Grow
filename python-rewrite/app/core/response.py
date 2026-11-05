"""统一响应信封与分页结果，字段名与 Java 版严格一致。"""
from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    code: int = 0
    message: str = "ok"
    data: T | None = None

    @classmethod
    def success(cls, data: T | None = None) -> ApiResponse[T]:
        return cls(code=0, message="ok", data=data)

    @classmethod
    def error(cls, code: int, message: str) -> ApiResponse[None]:
        return cls(code=code, message=message, data=None)


class PageResult(BaseModel, Generic[T]):
    items: list[T]
    total: int
    page: int
    size: int
