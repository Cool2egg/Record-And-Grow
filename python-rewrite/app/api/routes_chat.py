"""对话接口：非流式 + SSE 流式。"""
from __future__ import annotations

from fastapi import APIRouter

from app.agent import orchestrator
from app.api.sse import sse_response
from app.core.response import ApiResponse
from app.schemas.chat import ChatAskRequest

router = APIRouter(prefix="/api/chat", tags=["chat"])


@router.post("")
async def chat(request: ChatAskRequest):
    return ApiResponse.success(await orchestrator.ask(request))


@router.post("/stream")
async def stream(request: ChatAskRequest):
    # 预处理失败（未配置 key / 参数错误 / 会话不存在）在建立 SSE 之前抛出，
    # 由全局异常处理器返回正常的 HTTP 错误 + 信封。
    prepared = await orchestrator.prepare(request)
    return sse_response(orchestrator.stream(prepared, request))
