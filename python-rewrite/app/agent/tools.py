"""检索 Agent 的工具集：每个工具返回 JSON 字符串，失败返回 {"error": ...}。"""
from __future__ import annotations

import json
import time
from datetime import date, datetime, timedelta

from app.agent.context import AgentToolContext
from app.core.config import settings
from app.db.session import SessionLocal
from app.rag.keyword import extract_keywords
from app.rag.retrieval import retrieve_documents_fused, retrieve_timeline
from app.rag.timewindow import TimeBasis, TimeWindow, TimeWindowParser
from app.schemas.memory import MemoryEvidence
from app.services import graph as graph_service
from app.services import memory as memory_service

MAX_ITEMS = 8
ITEM_CHARS = 220

TOOL_DEFINITIONS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "search_timeline",
            "description": "查用户自己的时间线记录（做过什么、什么时候做的）。query 用自然语言，可带时间表达，例如「上个月跑步」「最近 7 天」。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "检索问题或关键词"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_documents",
            "description": "查用户上传的资料、笔记、条款、方法。返回文档块与出处，用于知识类问题。",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string", "description": "检索问题或关键词"}},
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_timeline_by_date",
            "description": "查某一天的全部时间线记录。date 格式 yyyy-MM-dd。",
            "parameters": {
                "type": "object",
                "properties": {"date": {"type": "string", "description": "日期，格式 yyyy-MM-dd"}},
                "required": ["date"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_memory",
            "description": "查用户已确认的长期偏好、目标、身份与习惯。只返回可信记忆，不返回待确认候选。",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string", "description": "要查的偏好或事实关键词"}},
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_graph",
            "description": "查文档知识图谱里的实体关系，适合「A 和 B 有什么关系」「某个概念属于什么」这类问题。",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string", "description": "实体名或关系问题"}},
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "propose_memory",
            "description": "把稳定的长期事实/偏好提交为待确认记忆。只提交长期有效的信息，不要提交一次性事件；用户确认后才会生效。",
            "parameters": {
                "type": "object",
                "properties": {
                    "content": {"type": "string", "description": "一条记忆，60 字以内"},
                    "type": {"type": "string", "enum": ["FACT", "INFERRED"]},
                },
                "required": ["content"],
            },
        },
    },
]


def _cache_key(query: str | None) -> str:
    if not query:
        return ""
    return " ".join(query.strip().lower().split())


class ToolExecutor:
    """绑定单个请求的上下文；文档/图谱检索结果按 query 去重缓存。"""

    def __init__(self, context: AgentToolContext) -> None:
        self.context = context
        self._document_cache: dict[str, tuple[str, int]] = {}
        self._graph_cache: dict[str, tuple[str, int]] = {}

    async def execute(self, name: str, raw_arguments: str) -> tuple[str, object]:
        """返回（给模型看的 JSON, Step 事件）。"""
        started = time.perf_counter()
        try:
            arguments = json.loads(raw_arguments or "{}")
        except ValueError:
            arguments = {}
        query = str(arguments.get("query") or arguments.get("date") or arguments.get("content") or "")

        if name == "search_timeline":
            result, step = await self._search_timeline(query, started)
        elif name == "get_timeline_by_date":
            result, step = await self._timeline_by_date(str(arguments.get("date") or ""), started)
        elif name == "search_documents":
            result, step = await self._search_documents(query, started)
        elif name == "search_memory":
            result, step = await self._search_memory(query, started)
        elif name == "search_graph":
            result, step = await self._search_graph(query, started)
        elif name == "propose_memory":
            result, step = await self._propose_memory(
                str(arguments.get("content") or ""), str(arguments.get("type") or "INFERRED"), started
            )
        else:
            step = self.context.record_step(name, query, 0, False, _elapsed(started), "未知工具")
            result = json.dumps({"error": "未知工具"}, ensure_ascii=False)
        return result, step

    async def _search_timeline(self, query: str, started: float) -> tuple[str, object]:
        try:
            window = TimeWindowParser().parse(query)
            keywords = extract_keywords(query)
            async with SessionLocal() as session:
                result = await retrieve_timeline(session, window, keywords)
            added = self.context.add_timeline(result)
            step = self.context.record_step(
                "search_timeline", query, result.retrieved, True, _elapsed(started), None
            )
            return _json(
                {
                    "count": result.retrieved,
                    "matched": result.matched,
                    "items": _item_list(added),
                }
            ), step
        except Exception as exc:  # noqa: BLE001
            step = self.context.record_step(
                "search_timeline", query, 0, False, _elapsed(started), "时间线检索失败"
            )
            return _json({"error": f"时间线检索失败: {exc}"}), step

    async def _timeline_by_date(self, raw_date: str, started: float) -> tuple[str, object]:
        try:
            day = date.fromisoformat(raw_date.strip())
        except ValueError:
            step = self.context.record_step(
                "get_timeline_by_date", raw_date, 0, False, _elapsed(started), "日期格式应为 yyyy-MM-dd"
            )
            return _json({"error": "日期格式应为 yyyy-MM-dd"}), step
        try:
            zone = TimeWindowParser().zone
            start = datetime.combine(day, datetime.min.time(), tzinfo=zone)
            window = TimeWindow(start, start + timedelta(days=1), TimeBasis.ABSOLUTE_DAY, False)
            async with SessionLocal() as session:
                result = await retrieve_timeline(session, window, [])
            added = self.context.add_timeline(result)
            step = self.context.record_step(
                "get_timeline_by_date", raw_date, result.retrieved, True, _elapsed(started), None
            )
            return _json(
                {"date": day.isoformat(), "count": result.retrieved, "items": _item_list(added)}
            ), step
        except Exception as exc:  # noqa: BLE001
            step = self.context.record_step(
                "get_timeline_by_date", raw_date, 0, False, _elapsed(started), "时间线检索失败"
            )
            return _json({"error": f"时间线检索失败: {exc}"}), step

    async def _search_documents(self, query: str, started: float) -> tuple[str, object]:
        key = _cache_key(query)
        cached = self._document_cache.get(key)
        if cached is not None:
            response, hits = cached
            step = self.context.record_step("search_documents", query, hits, True, 0, None)
            return response, step
        try:
            async with SessionLocal() as session:
                result = await retrieve_documents_fused(session, query)
            added = self.context.add_documents(result)
            response = _json(
                {
                    "searched": result.searched,
                    "degraded": result.degraded,
                    "degradeReason": result.degrade_reason.value if result.degrade_reason else None,
                    "count": len(result.hits),
                    "items": _item_list(added),
                }
            )
            self._document_cache[key] = (response, len(result.hits))
            step = self.context.record_step(
                "search_documents", query, len(result.hits), True, _elapsed(started), None
            )
            return response, step
        except Exception as exc:  # noqa: BLE001
            step = self.context.record_step(
                "search_documents", query, 0, False, _elapsed(started), "文档检索失败"
            )
            return _json({"error": f"文档检索失败: {exc}"}), step

    async def _search_memory(self, query: str, started: float) -> tuple[str, object]:
        if not settings.agent.memory_tools:
            step = self.context.record_step("search_memory", query, 0, True, _elapsed(started), None)
            return _json({"count": 0, "items": []}), step
        try:
            async with SessionLocal() as session:
                memories = await memory_service.search(session, query, settings.memory.search_limit)
            added = self.context.add_memories(memories)
            step = self.context.record_step(
                "search_memory", query, len(memories), True, _elapsed(started), None
            )
            return _json({"count": len(memories), "items": _item_list(added)}), step
        except Exception as exc:  # noqa: BLE001
            step = self.context.record_step(
                "search_memory", query, 0, False, _elapsed(started), "记忆检索失败"
            )
            return _json({"error": f"记忆检索失败: {exc}"}), step

    async def _search_graph(self, query: str, started: float) -> tuple[str, object]:
        key = _cache_key(query)
        cached = self._graph_cache.get(key)
        if cached is not None:
            response, hits = cached
            step = self.context.record_step("search_graph", query, hits, True, 0, None)
            return response, step
        if not settings.agent.graph_tools:
            step = self.context.record_step("search_graph", query, 0, True, _elapsed(started), None)
            return _json({"count": 0, "items": []}), step
        try:
            async with SessionLocal() as session:
                hits, searched, degraded, reason = await graph_service.search(
                    session, query, settings.graph.search_limit
                )
            added = self.context.add_graph(hits)
            response = _json(
                {
                    "searched": searched,
                    "degraded": degraded,
                    "degradeReason": reason,
                    "count": len(hits),
                    "items": _item_list(added),
                }
            )
            self._graph_cache[key] = (response, len(hits))
            step = self.context.record_step("search_graph", query, len(hits), True, _elapsed(started), None)
            return response, step
        except Exception as exc:  # noqa: BLE001
            step = self.context.record_step(
                "search_graph", query, 0, False, _elapsed(started), "图谱检索失败"
            )
            return _json({"error": f"图谱检索失败: {exc}"}), step

    async def _propose_memory(self, content: str, proposed_type: str, started: float) -> tuple[str, object]:
        if not settings.agent.propose_memory or not content.strip():
            step = self.context.record_step("propose_memory", content, 0, True, _elapsed(started), None)
            return _json({"saved": False}), step
        try:
            async with SessionLocal() as session:
                memory_id = await memory_service.add_candidate(
                    session,
                    content=content,
                    proposed_type=proposed_type.upper() if proposed_type else "INFERRED",
                    confidence=0.7,
                    evidence=[
                        MemoryEvidence(
                            type="conversation",
                            id=self.context.conversation_id,
                            excerpt=content[:80],
                        )
                    ],
                    source="AI_EXTRACTED",
                    conversation_id=self.context.conversation_id,
                )
            step = self.context.record_step(
                "propose_memory", content, 1 if memory_id else 0, True, _elapsed(started), None
            )
            return _json({"saved": memory_id is not None, "pendingId": memory_id or 0}), step
        except Exception as exc:  # noqa: BLE001
            step = self.context.record_step(
                "propose_memory", content, 0, False, _elapsed(started), "记忆提交失败"
            )
            return _json({"error": f"记忆提交失败: {exc}"}), step


def _item_list(items: list) -> list[dict]:
    result: list[dict] = []
    for item in items[:MAX_ITEMS]:
        entry: dict = {"ref": item.ref, "kind": item.kind}
        if item.kind == "timeline" and item.start_time is not None:
            entry["time"] = item.start_time.isoformat()
        if item.title:
            entry["title"] = item.title
        entry["text"] = (item.text or "")[:ITEM_CHARS]
        result.append(entry)
    return result


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False)


def _elapsed(started: float) -> int:
    return max(0, int((time.perf_counter() - started) * 1000))
