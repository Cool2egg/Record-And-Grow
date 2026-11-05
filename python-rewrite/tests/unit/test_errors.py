"""错误码与 AI 异常映射。"""
from __future__ import annotations

from app.core.errors import AiErrorType, AiException, BusinessException, ErrorCodes


def test_ai_error_maps_to_http_and_business_code():
    assert AiException(AiErrorType.NOT_CONFIGURED).error_code.code == ErrorCodes.AI_UNAVAILABLE.code
    assert AiException(AiErrorType.STREAM_INTERRUPTED).error_code.code == ErrorCodes.AI_UPSTREAM_ERROR.code


def test_business_exception_keeps_message():
    exc = BusinessException(ErrorCodes.NOT_FOUND, "会话不存在")
    assert exc.error_code.http_status == 404
    assert exc.message == "会话不存在"
