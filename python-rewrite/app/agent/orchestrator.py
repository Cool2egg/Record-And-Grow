"""对话编排：预处理 → SSE 事件流（Agent 两段式，失败回落经典链路）。"""
from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import datetime

from structlog.contextvars import get_contextvars

from app.agent.context import AgentToolContext
from app.agent.prompts import (
    ANSWER_SYSTEM,
    RESEARCH_SYSTEM,
    ContextAssembler,
    answer_input,
    chat_zone,
    research_input,
)
from app.agent.route import decide as decide_route
from app.agent.tools import TOOL_DEFINITIONS, ToolExecutor
from app.core.config import settings
from app.core.errors import AiErrorType, AiException, BusinessException, ErrorCodes
from app.core.logging import get_logger
from app.core.tasks import spawn
from app.db.session import SessionLocal
from app.rag.keyword import extract_keywords
from app.rag.models import Usage, get_model
from app.rag.retrieval import QueryRoute, retrieve
from app.rag.timewindow import TimeWindow, TimeWindowParser
from app.schemas.chat import (
    ChatAnswerResponse,
    ChatAskRequest,
    ChatUsage,
    Delta,
    Sources,
    StreamDone,
    StreamError,
    StreamStart,
)
from app.services import conversation as conversation_service
from app.services import memory as memory_service
from app.services import summarizer
from app.services.skills import get_skill_registry

log = get_logger(__name__)


@dataclass
class PreparedChat:
    request_id: str
    conversation_id: int
    question: str
    summary: str | None
    history: list[dict[str, str]]
    window: TimeWindow
    keywords: list[str]
    now: datetime


def _clamp_tokens(requested: int | None) -> int:
    cap = max(64, settings.chat.max_answer_tokens)
    base = requested if requested is not None else settings.chat.answer_max_tokens
    return max(64, min(base, cap))


def _temperature(requested: float | None) -> float:
    return requested if requested is not None else settings.chat.temperature


def _request_id() -> str:
    """复用 HTTP 层的 requestId，保证 SSE 事件与访问日志能对上。"""
    value = get_contextvars().get("request_id")
    return str(value) if value else str(uuid.uuid4())


async def prepare(request: ChatAskRequest) -> PreparedChat:
    client = get_model("chat")
    if not client.is_configured():
        raise AiException(AiErrorType.NOT_CONFIGURED, "AI 服务未配置")

    question = (request.question or "").strip()
    if not question:
        raise BusinessException(ErrorCodes.BAD_REQUEST, "问题不能为空")
    if len(question) > settings.chat.max_question_chars:
        raise BusinessException(
            ErrorCodes.BAD_REQUEST,
            f"问题过长，最多 {settings.chat.max_question_chars} 个字符",
        )

    async with SessionLocal() as session:
        conversation = await conversation_service.get_or_create(
            session, request.conversation_id, question
        )
        await conversation_service.ensure_title(session, conversation.id, question)
        summary, history = await conversation_service.history(session, conversation.id)

    return PreparedChat(
        request_id=_request_id(),
        conversation_id=conversation.id,
        question=question,
        summary=summary,
        history=history,
        window=TimeWindowParser().parse(question),
        keywords=extract_keywords(question),
        now=datetime.now(chat_zone()),
    )


async def stream(prepared: PreparedChat, request: ChatAskRequest) -> AsyncIterator[tuple[str, dict]]:
    """产出 (事件名, 载荷) 序列：start → step* → sources → delta* → done。"""
    request_id = prepared.request_id
    max_tokens = _clamp_tokens(request.max_tokens)
    temperature = _temperature(request.temperature)
    partial = False
    try:
        yield "start", StreamStart(
            request_id=request_id, conversation_id=prepared.conversation_id
        ).model_dump_json(by_alias=True)

        async with SessionLocal() as session:
            await conversation_service.append(
                session, prepared.conversation_id, "USER", prepared.question
            )

        if settings.agent.enabled:
            async for event in _agent_flow(prepared, max_tokens, temperature):
                if event[0] == "delta":
                    partial = True
                yield event
            return

        async for event in _classic_flow(prepared, max_tokens, temperature):
            if event[0] == "delta":
                partial = True
            yield event
    except (AiException, BusinessException) as exc:
        if isinstance(exc, AiException):
            code = exc.error_code.code
            error_type = exc.error_type.value
            message = exc.message
        else:
            code = exc.error_code.code
            error_type = AiErrorType.UPSTREAM_ERROR.value
            message = exc.message
        log.warning("chat_stream_failed", request_id=request_id, code=code, error_type=error_type)
        yield "error", StreamError(
            code=code,
            error_type=error_type,
            message=message,
            partial=partial,
            request_id=request_id,
        ).model_dump_json(by_alias=True)
    except Exception as exc:  # noqa: BLE001 - 兜底：前端收到 error 而不是挂住
        log.exception("chat_stream_unexpected_error", request_id=request_id, error=str(exc))
        yield "error", StreamError(
            code=ErrorCodes.INTERNAL_ERROR.code,
            error_type=AiErrorType.UPSTREAM_ERROR.value,
            message="系统内部错误",
            partial=partial,
            request_id=request_id,
        ).model_dump_json(by_alias=True)


# ---------------- Agent 两段式 ----------------

async def _agent_flow(
    prepared: PreparedChat, max_tokens: int, temperature: float
) -> AsyncIterator[tuple[str, dict]]:
    client = get_model("chat")
    context = AgentToolContext(
        window=prepared.window,
        keywords=prepared.keywords,
        question=prepared.question,
        conversation_id=prepared.conversation_id,
    )
    executor = ToolExecutor(context)

    messages: list[dict] = [
        {"role": "system", "content": RESEARCH_SYSTEM},
        {
            "role": "user",
            "content": research_input(
                prepared.summary,
                prepared.history,
                prepared.question,
                settings.agent.max_research_notes_chars * 4,
            ),
        },
    ]
    notes = ""
    for _ in range(max(1, settings.agent.research_max_iters)):
        completion = await client.complete(
            messages,
            max_tokens=800,
            temperature=0.2,
            tools=TOOL_DEFINITIONS,
            timeout_s=settings.agent.research_timeout_s,
        )
        if not completion.tool_calls:
            notes = completion.content
            break
        messages.append(
            {
                "role": "assistant",
                "content": completion.content or "",
                "tool_calls": [
                    {
                        "id": call.id,
                        "type": "function",
                        "function": {"name": call.name, "arguments": call.arguments},
                    }
                    for call in completion.tool_calls
                ],
            }
        )
        for call in completion.tool_calls:
            result, step = await executor.execute(call.name, call.arguments)
            messages.append({"role": "tool", "tool_call_id": call.id, "content": result})
            yield "step", step.model_dump_json(by_alias=True)

    sources = context.sources()
    yield "sources", sources.model_dump_json(by_alias=True)

    skills = get_skill_registry().match(prepared.question)
    answer_messages: list[dict] = [{"role": "system", "content": ANSWER_SYSTEM}]
    answer_messages.extend(prepared.history)
    answer_messages.append(
        {
            "role": "user",
            "content": answer_input(
                context.evidence_block(settings.agent.max_evidence_chars),
                prepared.summary,
                prepared.question,
                prepared.now,
                settings.agent.max_evidence_chars,
                skills,
            ),
        }
    )
    if notes:
        answer_messages[-1]["content"] += f"\n\n【检索笔记】\n{notes[: settings.agent.max_research_notes_chars]}"

    answer_state: dict = {}
    async for event in _stream_answer(answer_messages, max_tokens, temperature, answer_state):
        yield event
    await _persist_assistant(prepared, answer_state["answer"], sources, answer_state["usage"])


# ---------------- 经典链路 ----------------

async def _classic_flow(
    prepared: PreparedChat, max_tokens: int, temperature: float
) -> AsyncIterator[tuple[str, dict]]:
    assembler = ContextAssembler()
    decision = await decide_route(prepared.question)
    bundle = await retrieve(
        prepared.question, prepared.window, prepared.keywords, decision.route
    )
    if decision.route is QueryRoute.DIRECT:
        context_bundle = assembler.assemble_direct(prepared.question, prepared.now)
    else:
        context_bundle = assembler.assemble(
            bundle.timeline, bundle.documents, prepared.question, prepared.now
        )

    sources = context_bundle.sources
    yield "sources", sources.model_dump_json(by_alias=True)

    answer_state: dict = {}
    async for event in _stream_answer(context_bundle.messages, max_tokens, temperature, answer_state):
        yield event
    await _persist_assistant(prepared, answer_state["answer"], sources, answer_state["usage"])


async def _stream_answer(
    messages: list[dict],
    max_tokens: int,
    temperature: float,
    state: dict,
) -> AsyncIterator[tuple[str, dict]]:
    client = get_model("chat")
    chunks: list[str] = []
    usage = Usage()
    finish_reason: str | None = None
    async for chunk in client.stream(messages, max_tokens=max_tokens, temperature=temperature):
        if chunk.content:
            chunks.append(chunk.content)
            yield "delta", Delta(content=chunk.content).model_dump_json(by_alias=True)
        if chunk.usage is not None:
            usage = chunk.usage
        if chunk.finish_reason:
            finish_reason = chunk.finish_reason

    if finish_reason in ("insufficient_system_resource", "aborted"):
        raise AiException(AiErrorType.UPSTREAM_ERROR, "AI 服务返回异常")
    state["answer"] = "".join(chunks)
    state["usage"] = usage
    yield "done", StreamDone(
        finish_reason=finish_reason or "UNKNOWN",
        usage=ChatUsage(
            prompt_tokens=usage.prompt_tokens,
            completion_tokens=usage.completion_tokens,
            total_tokens=usage.total_tokens,
        ),
    ).model_dump_json(by_alias=True)


async def _persist_assistant(
    prepared: PreparedChat, answer: str, sources: Sources, usage: Usage
) -> None:
    try:
        async with SessionLocal() as session:
            await conversation_service.append(
                session,
                prepared.conversation_id,
                "ASSISTANT",
                answer,
                sources.model_dump_json(by_alias=True),
            )
    except Exception as exc:  # noqa: BLE001 - 落库失败不影响已发出的回答
        log.warning("assistant_persist_failed", error=str(exc))
        return

    spawn(
        memory_service.extract_candidates(prepared.question, answer, prepared.conversation_id),
        name="memory_extract",
    )
    spawn(
        summarizer.maybe_summarize(prepared.conversation_id),
        name="conversation_summarize",
    )


# ---------------- 非流式 ----------------

async def ask(request: ChatAskRequest) -> ChatAnswerResponse:
    prepared = await prepare(request)
    assembler = ContextAssembler()
    decision = await decide_route(prepared.question)
    bundle = await retrieve(prepared.question, prepared.window, prepared.keywords, decision.route)
    if decision.route is QueryRoute.DIRECT:
        context_bundle = assembler.assemble_direct(prepared.question, prepared.now)
    else:
        context_bundle = assembler.assemble(
            bundle.timeline, bundle.documents, prepared.question, prepared.now
        )

    client = get_model("chat")
    completion = await client.complete(
        context_bundle.messages,
        max_tokens=_clamp_tokens(request.max_tokens),
        temperature=_temperature(request.temperature),
    )
    sources = context_bundle.sources
    try:
        async with SessionLocal() as session:
            await conversation_service.append(
                session, prepared.conversation_id, "USER", prepared.question
            )
            await conversation_service.append(
                session,
                prepared.conversation_id,
                "ASSISTANT",
                completion.content,
                sources.model_dump_json(by_alias=True),
            )
    except Exception as exc:  # noqa: BLE001
        log.warning("chat_persist_failed", error=str(exc))

    return ChatAnswerResponse(
        request_id=prepared.request_id,
        content=completion.content,
        sources=sources.items,
        retrieval=sources.retrieval,
        finish_reason=completion.finish_reason or "UNKNOWN",
        usage=ChatUsage(
            prompt_tokens=completion.usage.prompt_tokens,
            completion_tokens=completion.usage.completion_tokens,
            total_tokens=completion.usage.total_tokens,
        ),
    )
