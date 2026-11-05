"""全局异常处理：把异常统一翻译成 ApiResponse 信封。"""
from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import DBAPIError, InterfaceError, OperationalError

from app.core.errors import BusinessException, ErrorCodes
from app.core.logging import get_logger
from app.core.response import ApiResponse

log = get_logger(__name__)


def _conn_error_message(message: str | None) -> bool:
    text = (message or "").lower()
    return any(
        token in text
        for token in (
            "connection refused",
            "could not connect",
            "connection is closed",
            "connection reset",
            "server closed the connection",
            "timeout",
            "network is unreachable",
            "password authentication failed",
        )
    )


def install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(BusinessException)
    async def handle_business(_: Request, exc: BusinessException) -> JSONResponse:
        log.warning("business_error", code=exc.error_code.code, message=exc.message)
        return JSONResponse(
            status_code=exc.error_code.http_status,
            content=ApiResponse.error(exc.error_code.code, exc.message).model_dump(),
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        detail = "; ".join(
            f"{'.'.join(str(part) for part in error.get('loc', [])[1:])}: {error.get('msg')}"
            for error in exc.errors()
        )
        return JSONResponse(
            status_code=400,
            content=ApiResponse.error(ErrorCodes.BAD_REQUEST.code, f"参数校验失败: {detail}").model_dump(),
        )

    @app.exception_handler(ConnectionError)
    async def handle_connection_error(_: Request, exc: ConnectionError) -> JSONResponse:
        # 连接池建立连接失败时，asyncpg 可能直接抛 ConnectionRefusedError，而不是 OperationalError
        log.warning("connection_unavailable", error=str(exc))
        return JSONResponse(
            status_code=ErrorCodes.DB_UNAVAILABLE.http_status,
            content=ApiResponse.error(
                ErrorCodes.DB_UNAVAILABLE.code, ErrorCodes.DB_UNAVAILABLE.message
            ).model_dump(),
        )

    @app.exception_handler(OperationalError)
    @app.exception_handler(InterfaceError)
    @app.exception_handler(DBAPIError)
    async def handle_db_error(_: Request, exc: Exception) -> JSONResponse:
        if _conn_error_message(str(exc)):
            log.warning("database_unavailable", error=str(exc))
            return JSONResponse(
                status_code=ErrorCodes.DB_UNAVAILABLE.http_status,
                content=ApiResponse.error(
                    ErrorCodes.DB_UNAVAILABLE.code, ErrorCodes.DB_UNAVAILABLE.message
                ).model_dump(),
            )
        log.exception("database_error", error=str(exc))
        return JSONResponse(
            status_code=ErrorCodes.INTERNAL_ERROR.http_status,
            content=ApiResponse.error(
                ErrorCodes.INTERNAL_ERROR.code, ErrorCodes.INTERNAL_ERROR.message
            ).model_dump(),
        )

    @app.exception_handler(Exception)
    async def handle_unexpected(_: Request, exc: Exception) -> JSONResponse:
        log.exception("unhandled_error", error=str(exc))
        return JSONResponse(
            status_code=500,
            content=ApiResponse.error(
                ErrorCodes.INTERNAL_ERROR.code, ErrorCodes.INTERNAL_ERROR.message
            ).model_dump(),
        )
