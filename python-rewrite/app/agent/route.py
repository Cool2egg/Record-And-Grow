"""意图路由：决定查时间线、文档，还是两路都查；失败一律回落 BOTH。"""
from __future__ import annotations

import json

from app.core.config import settings
from app.core.logging import get_logger
from app.rag.models import get_model
from app.rag.retrieval import QueryRoute

log = get_logger(__name__)

_ROUTE_PROMPT = """判断下面这个问题需要检索哪类资料。只输出 JSON，不要解释：
{{"route": "BOTH" | "TIMELINE" | "DOCUMENTS" | "DIRECT"}}

- TIMELINE：问用户自己做过什么、什么时候做的；
- DOCUMENTS：问用户上传的资料、笔记、条款、方法里的知识；
- BOTH：两类都可能需要，或无法判断；
- DIRECT：闲聊、简单常识，完全不需要用户自己的资料。

问题：{question}
"""


class QueryDecision:
    def __init__(self, route: QueryRoute, fallback: bool = False, reason: str | None = None) -> None:
        self.route = route
        self.fallback = fallback
        self.reason = reason


async def decide(question: str) -> QueryDecision:
    if not settings.chat.routing.enabled:
        return QueryDecision(QueryRoute.BOTH)
    client = get_model("chat")
    if not client.is_configured():
        return QueryDecision(QueryRoute.BOTH)
    try:
        completion = await client.complete(
            [
                {
                    "role": "user",
                    "content": _ROUTE_PROMPT.format(question=question[: settings.chat.routing.max_question_chars]),
                }
            ],
            max_tokens=64,
            temperature=0.0,
            timeout_s=settings.chat.routing.timeout_s,
        )
        route = _parse_route(completion.content)
        if route is None:
            return QueryDecision(QueryRoute.BOTH, fallback=True, reason="unparseable")
        return QueryDecision(route)
    except Exception as exc:  # noqa: BLE001 - 路由失败不是错误，回落全查
        log.warning("routing_fallback", error=str(exc))
        return QueryDecision(QueryRoute.BOTH, fallback=True, reason=str(exc))


def _parse_route(raw: str) -> QueryRoute | None:
    if not raw:
        return None
    start = raw.find("{")
    end = raw.rfind("}")
    try:
        payload = json.loads(raw[start:end + 1]) if start >= 0 and end > start else {}
    except ValueError:
        return None
    value = str(payload.get("route") or "").strip().upper()
    try:
        return QueryRoute(value)
    except ValueError:
        return None
