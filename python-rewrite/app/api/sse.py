"""SSE 响应：事件名 + JSON 字符串；15s 心跳注释。"""
from __future__ import annotations

from collections.abc import AsyncIterator

from sse_starlette.sse import EventSourceResponse


def sse_response(events: AsyncIterator[tuple[str, str]]) -> EventSourceResponse:
    async def generator() -> AsyncIterator[dict[str, str]]:
        async for name, data in events:
            yield {"event": name, "data": data}

    return EventSourceResponse(
        generator(),
        ping=15,
        headers={
            "X-Accel-Buffering": "no",
            "Cache-Control": "no-cache",
        },
    )
