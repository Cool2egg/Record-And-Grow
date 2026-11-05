"""MCP stateless server：JSON-RPC 2.0，暴露只读检索工具。"""
from __future__ import annotations

import json

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from app.agent.context import AgentToolContext
from app.agent.tools import TOOL_DEFINITIONS, ToolExecutor
from app.core.config import settings
from app.core.logging import get_logger
from app.rag.timewindow import TimeWindowParser

log = get_logger(__name__)

router = APIRouter(tags=["mcp"])

_TOOL_NAMES = {definition["function"]["name"] for definition in TOOL_DEFINITIONS}


def _tools_payload() -> list[dict]:
    return [
        {
            "name": definition["function"]["name"],
            "description": definition["function"].get("description", ""),
            "inputSchema": definition["function"].get("parameters", {"type": "object"}),
        }
        for definition in TOOL_DEFINITIONS
    ]


def _authorized(request: Request) -> bool:
    token = settings.mcp.auth_token.strip()
    if not token:
        return True
    header = request.headers.get("authorization", "")
    return header.lower().startswith("bearer ") and header[7:].strip() == token


@router.post(settings.mcp.endpoint)
async def mcp(request: Request):
    if not _authorized(request):
        return JSONResponse(
            status_code=401,
            content={"jsonrpc": "2.0", "error": {"code": -32001, "message": "未授权"}, "id": None},
        )
    if not settings.mcp.enabled:
        return JSONResponse(
            status_code=404,
            content={"jsonrpc": "2.0", "error": {"code": -32601, "message": "MCP 未启用"}, "id": None},
        )
    try:
        payload = await request.json()
    except ValueError:
        return JSONResponse(
            status_code=400,
            content={"jsonrpc": "2.0", "error": {"code": -32700, "message": "解析失败"}, "id": None},
        )

    method = payload.get("method")
    request_id = payload.get("id")
    params = payload.get("params") or {}

    # 通知（无 id）不需要响应
    if request_id is None:
        return Response(status_code=202)

    if method == "initialize":
        result = {
            "protocolVersion": params.get("protocolVersion") or "2024-11-05",
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": settings.mcp.server_name, "version": settings.mcp.server_version},
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": _tools_payload()}
    elif method == "tools/call":
        name = str(params.get("name") or "")
        arguments = params.get("arguments") or {}
        if name not in _TOOL_NAMES or name == "propose_memory":
            return JSONResponse(
                status_code=200,
                content={
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {"code": -32602, "message": f"未知工具: {name}"},
                },
            )
        query = str(arguments.get("query") or arguments.get("date") or "")
        context = AgentToolContext(
            window=TimeWindowParser().parse(query),
            keywords=[],
            question=query,
            conversation_id=None,
        )
        text, _step = await ToolExecutor(context).execute(name, json.dumps(arguments, ensure_ascii=False))
        result = {"content": [{"type": "text", "text": text}], "isError": False}
    else:
        return JSONResponse(
            status_code=200,
            content={
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": f"未知方法: {method}"},
            },
        )

    return JSONResponse(content={"jsonrpc": "2.0", "id": request_id, "result": result})
