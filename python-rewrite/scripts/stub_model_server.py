"""本地联调用的 OpenAI 兼容桩服务（仅开发/验收，不参与生产）。

提供：
- POST /chat/completions：普通回答、SSE 流式、工具调用（第一轮返回 search_timeline）
- POST /embeddings：固定 1024 维向量，保证相似度检索可用

启动：uv run uvicorn stub_model_server:app --app-dir scripts --port 9099
"""
from __future__ import annotations

import asyncio
import json

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

app = FastAPI()

_DIM = 1024
_VECTOR = [1.0] + [0.0] * (_DIM - 1)
_USAGE = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}


def _text_of(messages: list[dict]) -> str:
    return "\n".join(str(message.get("content") or "") for message in messages)


def _answer_for(text: str) -> str:
    if "提炼" in text:
        return '[{"content": "用户喜欢跑步", "type": "FACT"}]'
    if "压缩成" in text:
        return "用户最近在联调系统。"
    if "抽取实体关系" in text:
        return '[{"source": "测试实体A", "relation": "包含", "target": "测试实体B"}]'
    return "根据材料，你最近在测试系统。[T1]"


@app.post("/chat/completions")
async def chat_completions(request: Request):
    body = await request.json()
    messages = body.get("messages") or []
    text = _text_of(messages)
    has_tools = bool(body.get("tools"))
    already_used_tool = any(message.get("role") == "tool" for message in messages)

    if "触发错误" in text:
        return JSONResponse(status_code=500, content={"error": {"message": "stub failure"}})

    if has_tools and not already_used_tool:
        question = ""
        for message in reversed(messages):
            if message.get("role") == "user":
                question = str(message.get("content") or "")
                break
        return JSONResponse(
            {
                "choices": [
                    {
                        "message": {
                            "content": "",
                            "tool_calls": [
                                {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {
                                        "name": "search_timeline",
                                        "arguments": json.dumps({"query": question[:200]}),
                                    },
                                },
                                {
                                    "id": "call_2",
                                    "type": "function",
                                    "function": {
                                        "name": "search_documents",
                                        "arguments": json.dumps({"query": question[:200]}),
                                    },
                                },
                            ],
                        },
                        "finish_reason": "tool_calls",
                    }
                ],
                "usage": _USAGE,
            }
        )

    content = _answer_for(text)
    if body.get("stream"):
        return StreamingResponse(_stream(content), media_type="text/event-stream")
    return JSONResponse(
        {
            "choices": [{"message": {"content": content}, "finish_reason": "stop"}],
            "usage": _USAGE,
        }
    )


async def _stream(content: str):
    for piece in _pieces(content):
        chunk = {"choices": [{"delta": {"content": piece}, "finish_reason": None}]}
        yield f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
        await asyncio.sleep(0)
    final = {"choices": [{"delta": {}, "finish_reason": "stop"}], "usage": _USAGE}
    yield f"data: {json.dumps(final, ensure_ascii=False)}\n\n"
    yield "data: [DONE]\n\n"


def _pieces(content: str) -> list[str]:
    if not content:
        return []
    size = max(1, len(content) // 4)
    return [content[i : i + size] for i in range(0, len(content), size)]


@app.post("/embeddings")
async def embeddings(request: Request):
    body = await request.json()
    inputs = body.get("input")
    if isinstance(inputs, str):
        inputs = [inputs]
    items = inputs or []
    return JSONResponse(
        {
            "data": [
                {"index": index, "embedding": _VECTOR}
                for index, _ in enumerate(items)
            ]
        }
    )
