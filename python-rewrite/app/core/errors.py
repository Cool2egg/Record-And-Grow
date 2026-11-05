"""错误码与业务异常。

业务码（``code``）与 HTTP 状态码分离：code 用于前端按业务规则处理，
HTTP 状态码用于传输层语义。与 Java 版 ``ErrorCode`` 一一对应。
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


@dataclass(frozen=True)
class ErrorCode:
    code: int
    message: str
    http_status: int


class ErrorCodes:
    SUCCESS = ErrorCode(0, "ok", 200)
    BAD_REQUEST = ErrorCode(40000, "请求参数错误", 400)
    NOT_FOUND = ErrorCode(40400, "资源不存在", 404)
    INTERNAL_ERROR = ErrorCode(50000, "系统内部错误", 500)
    DB_UNAVAILABLE = ErrorCode(50300, "数据库不可用", 503)
    AI_UNAVAILABLE = ErrorCode(50301, "AI 服务暂时不可用", 503)
    AI_UPSTREAM_ERROR = ErrorCode(50201, "AI 服务返回异常", 502)
    UNSUPPORTED_MEDIA_TYPE = ErrorCode(41500, "不支持的文件类型", 415)
    DOCUMENT_PARSE_FAILED = ErrorCode(42200, "文档解析失败", 422)
    DOCUMENT_BUSY = ErrorCode(40900, "文档正在处理中", 409)
    PAYLOAD_TOO_LARGE = ErrorCode(41300, "文件过大", 413)
    QUEUE_UNAVAILABLE = ErrorCode(50302, "消息队列不可用", 503)


class AiErrorType(str, Enum):
    NOT_CONFIGURED = "NOT_CONFIGURED"
    RATE_LIMITED = "RATE_LIMITED"
    TIMEOUT = "TIMEOUT"
    UPSTREAM_ERROR = "UPSTREAM_ERROR"
    INVALID_REQUEST = "INVALID_REQUEST"
    BAD_RESPONSE = "BAD_RESPONSE"
    STREAM_INTERRUPTED = "STREAM_INTERRUPTED"


class BusinessException(Exception):
    """可预期的业务异常，由全局处理器翻译成对应 HTTP 状态 + 信封。"""

    def __init__(self, error_code: ErrorCode, message: str | None = None) -> None:
        super().__init__(message or error_code.message)
        self.error_code = error_code
        self.message = message or error_code.message


class AiException(BusinessException):
    """AI 调用失败。type 区分未配置 / 限流 / 超时 / 上游异常等。"""

    def __init__(
        self,
        error_type: AiErrorType,
        message: str | None = None,
        provider: str | None = None,
        status_code: int | None = None,
    ) -> None:
        code = _code_for(error_type)
        super().__init__(code, message)
        self.error_type = error_type
        self.provider = provider
        self.status_code = status_code


def _code_for(error_type: AiErrorType) -> ErrorCode:
    if error_type in (
        AiErrorType.INVALID_REQUEST,
        AiErrorType.BAD_RESPONSE,
        AiErrorType.STREAM_INTERRUPTED,
    ):
        return ErrorCodes.AI_UPSTREAM_ERROR
    return ErrorCodes.AI_UNAVAILABLE
