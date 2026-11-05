"""上下文组装与 sources 不变式。"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

from app.agent.prompts import ContextAssembler
from app.rag.retrieval import DocumentRetrievalResult, TimelineResult
from app.rag.rrf import DocumentHit
from app.rag.timewindow import TimeBasis, TimeWindow

NOW = datetime(2026, 11, 3, 15, 30, tzinfo=UTC)
WINDOW = TimeWindow(NOW - timedelta(days=30), NOW, TimeBasis.DEFAULT, False)


def entry(entry_id: int, content: str, offset_hours: int = 0) -> SimpleNamespace:
    start = NOW - timedelta(hours=offset_hours)
    return SimpleNamespace(id=entry_id, content=content, start_time=start, end_time=None)


def timeline_result(count: int = 3) -> TimelineResult:
    items = [entry(i + 1, f"记录{i + 1}", offset_hours=i) for i in range(count)]
    return TimelineResult(
        items=items,
        window=WINDOW,
        matched=count,
        retrieved=count,
        limit_truncated=False,
        keywords=[],
        keyword_fallback=False,
    )


def test_timeline_only_sources_invariant():
    bundle = ContextAssembler().assemble(timeline_result(3), DocumentRetrievalResult.not_searched(), "我做了什么", NOW)
    sources = bundle.sources
    assert [item.ref for item in sources.items] == ["T1", "T2", "T3"]
    assert sources.retrieval.total_included == len(sources.items)
    assert sources.retrieval.total_included == sources.retrieval.included
    assert sources.retrieval.document_searched is False
    assert bundle.messages[0]["role"] == "system"
    assert "【问题】\n我做了什么" in bundle.messages[1]["content"]


def test_documents_are_appended_after_timeline():
    documents = DocumentRetrievalResult(
        hits=[
            DocumentHit(
                chunk_id=11,
                document_id=5,
                title="学习计划",
                heading="第一章",
                content="每天读三十页书。",
                score=0.91234567,
                char_start=0,
                char_end=8,
                chunk_index=0,
            )
        ],
        searched=True,
        top_score=0.91,
    )
    bundle = ContextAssembler().assemble(timeline_result(2), documents, "学习计划怎么执行", NOW)
    sources = bundle.sources
    assert [item.ref for item in sources.items] == ["T1", "T2", "D1"]
    assert sources.items[-1].type == "document"
    assert sources.items[-1].chunk_id == 11
    assert sources.items[-1].score == 0.912346
    meta = sources.retrieval
    assert meta.total_included == len(sources.items)
    assert meta.total_included == meta.included + meta.document_included
    assert meta.document_searched is True


def test_direct_path_has_no_sources():
    bundle = ContextAssembler().assemble_direct("你好", NOW)
    assert bundle.sources.items == []
    assert bundle.sources.retrieval.total_included == 0
