"""HTTP 请求日志：生成/透传 requestId，绑定 structlog 上下文，记录耗时与状态码。

用纯 ASGI 中间件而不是 BaseHTTPMiddleware：后者会包一层响应体迭代，
对 SSE 的流式输出与取消语义有影响。
"""
from __future__ import annotations

import time
import uuid

import structlog
from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import get_logger

log = get_logger(__name__)


class RequestContextMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers") or [])
        incoming = headers.get(b"x-request-id")
        request_id = incoming.decode("latin-1") if incoming else str(uuid.uuid4())
        structlog.contextvars.bind_contextvars(request_id=request_id)
        started = time.perf_counter()
        status_code = 0

        async def send_wrapper(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
                MutableHeaders(scope=message)["X-Request-Id"] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception:
            log.exception(
                "http_request_failed",
                method=scope.get("method"),
                path=scope.get("path"),
            )
            raise
        finally:
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            log.info(
                "http_request",
                method=scope.get("method"),
                path=scope.get("path"),
                status=status_code,
                ms=elapsed_ms,
            )
            structlog.contextvars.clear_contextvars()
