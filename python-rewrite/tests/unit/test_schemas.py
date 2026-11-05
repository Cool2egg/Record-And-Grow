"""Schema：camelCase 字段、UTC 时间序列化、SSE 载荷。"""
from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta, timezone

from app.schemas.chat import ChatAskRequest, Delta, RetrievalMeta, SourceItem, Sources
from app.schemas.memory import Memory
from app.schemas.timeline import TimelineEntry

CST = timezone(timedelta(hours=8))


def test_timeline_entry_serializes_camelcase_and_utc():
    entry = TimelineEntry(
        id=1,
        content="跑步",
        start_time=datetime(2026, 11, 3, 15, 30, tzinfo=CST),
        end_time=None,
        created_at=datetime(2026, 11, 3, 15, 30, tzinfo=CST),
        updated_at=datetime(2026, 11, 3, 15, 30, tzinfo=CST),
    )
    payload = json.loads(entry.model_dump_json(by_alias=True))
    assert payload["startTime"] == "2026-11-03T07:30:00+00:00"
    assert payload["endTime"] is None
    assert "start_time" not in payload


def test_chat_request_accepts_camelcase_alias():
    request = ChatAskRequest.model_validate({"question": "你好", "conversationId": 5, "maxTokens": 100})
    assert request.conversation_id == 5
    assert request.max_tokens == 100


def test_sources_roundtrip_and_invariant():
    sources = Sources(
        items=[
            SourceItem(ref="T1", type="timeline", id=1, excerpt="记录",
                       start_time=datetime(2026, 11, 3, tzinfo=UTC)),
        ],
        retrieval=RetrievalMeta(
            window_start=None,
            window_end=None,
            time_basis="DEFAULT",
            clamped=False,
            matched=1,
            retrieved=1,
            included=1,
            limit_truncated=False,
            budget_truncated=False,
            keywords=[],
            keyword_fallback=False,
            document_searched=False,
            documents_retrieved=0,
            document_included=0,
            total_included=1,
            documents_degraded=False,
            degrade_reason=None,
        ),
    )
    payload = json.loads(sources.model_dump_json(by_alias=True))
    assert payload["retrieval"]["totalIncluded"] == payload["retrieval"]["included"] == len(payload["items"])
    assert payload["items"][0]["startTime"] == "2026-11-03T00:00:00+00:00"
    assert payload["retrieval"]["timeBasis"] == "DEFAULT"


def test_memory_evidence_defaults_to_empty_list():
    memory = Memory(
        id=1,
        type="FACT",
        content="喜欢跑步",
        source="USER",
        created_at=datetime.now(UTC),
        updated_at=datetime.now(UTC),
    )
    assert memory.evidence == []


def test_delta_payload():
    assert json.loads(Delta(content="你").model_dump_json(by_alias=True)) == {"content": "你"}
