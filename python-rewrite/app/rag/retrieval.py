"""检索：时间线 / 文档向量 / 关键词融合 / 证据闸门 / 并行编排。"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from enum import Enum

from sqlalchemy import Select, func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.errors import AiErrorType, AiException
from app.core.logging import get_logger
from app.db.session import SessionLocal
from app.models.document import Document
from app.models.timeline import TimelineEntry
from app.rag.evidence import evidence_gate_keep
from app.rag.keyword import extract_keywords, segment_for_search
from app.rag.models import get_embedding_client, get_rerank_client
from app.rag.rrf import DocumentHit, rrf_fuse
from app.rag.selection import select_documents
from app.rag.timewindow import TimeWindow, TimeWindowParser

log = get_logger(__name__)

RETRIEVAL_LIMIT = 100
EXPECTED_DIMENSIONS = 1024


class QueryRoute(str, Enum):
    BOTH = "BOTH"
    TIMELINE = "TIMELINE"
    DOCUMENTS = "DOCUMENTS"
    DIRECT = "DIRECT"


class DocumentDegradeReason(str, Enum):
    NOT_CONFIGURED = "NOT_CONFIGURED"
    DIMENSION = "DIMENSION"
    TIMEOUT = "TIMEOUT"
    RATE_LIMITED = "RATE_LIMITED"
    AUTH = "AUTH"
    UPSTREAM = "UPSTREAM"
    BAD_RESPONSE = "BAD_RESPONSE"


@dataclass
class TimelineResult:
    items: list[TimelineEntry]
    window: TimeWindow
    matched: int
    retrieved: int
    limit_truncated: bool
    keywords: list[str]
    keyword_fallback: bool


@dataclass
class DocumentRetrievalResult:
    hits: list[DocumentHit] = field(default_factory=list)
    searched: bool = False
    degraded: bool = False
    degrade_reason: DocumentDegradeReason | None = None
    top_score: float | None = None
    query_embedding_ms: int = 0
    sql_ms: int = 0

    @classmethod
    def not_searched(cls) -> DocumentRetrievalResult:
        return cls()

    @classmethod
    def degraded_result(
        cls, reason: DocumentDegradeReason, embedding_ms: int = 0
    ) -> DocumentRetrievalResult:
        return cls(degraded=True, degrade_reason=reason, query_embedding_ms=embedding_ms)


@dataclass
class RetrievalBundle:
    timeline: TimelineResult
    documents: DocumentRetrievalResult
    timeline_ms: int = 0
    document_ms: int = 0


# ---------------- 时间线 ----------------

def _timeline_filter(window: TimeWindow, keywords: list[str]) -> Select:
    query = select(TimelineEntry).where(
        TimelineEntry.deleted.is_(False),
        TimelineEntry.start_time < window.end,
        (TimelineEntry.end_time.is_(None)) | (TimelineEntry.end_time > window.start),
    )
    for keyword in keywords:
        query = query.where(TimelineEntry.content.ilike(f"%{_escape_like(keyword)}%"))
    return query


def _escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


async def retrieve_timeline(
    session: AsyncSession, window: TimeWindow, keywords: list[str]
) -> TimelineResult:
    safe_keywords = list(keywords or [])
    count_stmt = select(func.count()).select_from(_timeline_filter(window, safe_keywords).subquery())
    total = int((await session.execute(count_stmt)).scalar_one())

    fallback = False
    if safe_keywords and total == 0:
        count_stmt = select(func.count()).select_from(_timeline_filter(window, []).subquery())
        total = int((await session.execute(count_stmt)).scalar_one())
        fallback = True
        safe_keywords_used: list[str] = []
    else:
        safe_keywords_used = safe_keywords

    stmt = (
        _timeline_filter(window, safe_keywords_used)
        .order_by(TimelineEntry.start_time.desc(), TimelineEntry.id.desc())
        .limit(RETRIEVAL_LIMIT)
    )
    rows = list((await session.execute(stmt)).scalars().all())
    return TimelineResult(
        items=rows,
        window=window,
        matched=total,
        retrieved=len(rows),
        limit_truncated=total > len(rows),
        keywords=safe_keywords,
        keyword_fallback=fallback,
    )


# ---------------- 文档向量 ----------------

def _degrade_reason(error_type: AiErrorType) -> DocumentDegradeReason:
    return {
        AiErrorType.TIMEOUT: DocumentDegradeReason.TIMEOUT,
        AiErrorType.RATE_LIMITED: DocumentDegradeReason.RATE_LIMITED,
        AiErrorType.NOT_CONFIGURED: DocumentDegradeReason.NOT_CONFIGURED,
        AiErrorType.UPSTREAM_ERROR: DocumentDegradeReason.UPSTREAM,
        AiErrorType.INVALID_REQUEST: DocumentDegradeReason.UPSTREAM,
        AiErrorType.BAD_RESPONSE: DocumentDegradeReason.BAD_RESPONSE,
        AiErrorType.STREAM_INTERRUPTED: DocumentDegradeReason.UPSTREAM,
    }.get(error_type, DocumentDegradeReason.UPSTREAM)


def _vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in vector) + "]"


_DOCUMENT_SEARCH_SQL = text(
    """
    SELECT c.id AS chunk_id,
           c.document_id AS document_id,
           d.title AS title,
           c.heading AS heading,
           c.content AS content,
           c.char_start AS char_start,
           c.char_end AS char_end,
           c.chunk_index AS chunk_index,
           c.embedding <=> CAST(:vec AS vector) AS distance
    FROM document_chunk c
    JOIN document d ON d.id = c.document_id
    WHERE d.status = 'READY'
      AND d.deleted = FALSE
      AND c.embedding IS NOT NULL
    ORDER BY c.embedding <=> CAST(:vec AS vector)
    LIMIT :scan_limit
    """
)


async def _has_ready_document(session: AsyncSession) -> bool:
    stmt = select(func.count()).select_from(
        select(Document.id).where(Document.status == "READY", Document.deleted.is_(False)).subquery()
    )
    return int((await session.execute(stmt)).scalar_one()) > 0


async def retrieve_documents(session: AsyncSession, question: str) -> DocumentRetrievalResult:
    props = settings.chat.documents
    if not props.enabled:
        return DocumentRetrievalResult.not_searched()
    if not question or not question.strip():
        return DocumentRetrievalResult.not_searched()
    if not await _has_ready_document(session):
        return DocumentRetrievalResult.not_searched()

    embedding_client = get_embedding_client()
    if not embedding_client.is_configured():
        return DocumentRetrievalResult.degraded_result(DocumentDegradeReason.NOT_CONFIGURED)
    if embedding_client.dimensions != EXPECTED_DIMENSIONS:
        return DocumentRetrievalResult.degraded_result(DocumentDegradeReason.DIMENSION)

    started = time.perf_counter()
    try:
        vectors = await embedding_client.embed(
            [question.strip()],
            timeout_s=props.query_timeout_s,
            max_attempts=props.query_max_attempts,
        )
    except AiException as exc:
        return DocumentRetrievalResult.degraded_result(
            _degrade_reason(exc.error_type), _elapsed_ms(started)
        )
    embedding_ms = _elapsed_ms(started)

    if not vectors or len(vectors) != 1 or vectors[0] is None:
        return DocumentRetrievalResult.degraded_result(
            DocumentDegradeReason.BAD_RESPONSE, embedding_ms
        )
    if len(vectors[0]) != embedding_client.dimensions:
        return DocumentRetrievalResult.degraded_result(DocumentDegradeReason.DIMENSION, embedding_ms)

    sql_started = time.perf_counter()
    try:
        rows = (
            await session.execute(
                _DOCUMENT_SEARCH_SQL,
                {"vec": _vector_literal(vectors[0]), "scan_limit": max(1, props.scan_limit)},
            )
        ).mappings().all()
    except Exception:
        # SQL 异常直接上抛：连接类 → 503，其它 → 500，不伪装成「没命中」
        await session.rollback()
        raise
    sql_ms = _elapsed_ms(sql_started)

    top_score: float | None = None
    candidates: list[DocumentHit] = []
    for row in rows:
        distance = row["distance"]
        if distance is None:
            continue
        score = 1.0 - float(distance)
        if top_score is None or score > top_score:
            top_score = score
        if score < props.similarity_threshold:
            continue
        candidates.append(
            DocumentHit(
                chunk_id=row["chunk_id"],
                document_id=row["document_id"],
                title=row["title"],
                heading=row["heading"],
                content=row["content"],
                score=score,
                char_start=row["char_start"],
                char_end=row["char_end"],
                chunk_index=row["chunk_index"] if row["chunk_index"] is not None else -1,
            )
        )
    candidates.sort(key=lambda hit: (-hit.score, hit.document_id, hit.chunk_index, hit.chunk_id))
    selected = select_documents(candidates, max(1, props.top_k), max(1, props.max_per_document))
    return DocumentRetrievalResult(
        hits=selected,
        searched=True,
        top_score=top_score,
        query_embedding_ms=embedding_ms,
        sql_ms=sql_ms,
    )


# ---------------- 关键词通道 ----------------

_KEYWORD_SQL_PG_SEARCH = text(
    """
    SELECT c.id AS chunk_id, c.document_id AS document_id, d.title AS title,
           c.heading AS heading, c.content AS content,
           c.char_start AS char_start, c.char_end AS char_end, c.chunk_index AS chunk_index,
           paradedb.score(c.id) AS score
    FROM document_chunk c
    JOIN document d ON d.id = c.document_id
    WHERE d.status = 'READY' AND d.deleted = FALSE AND c.content @@@ :q
    ORDER BY score DESC
    LIMIT :limit
    """
)


def _keyword_sql(terms: list[str]) -> tuple:
    """按分词结果拼 OR ILIKE 查询，命中词数作为分数（不依赖 pg_search/扩展）。"""
    matches = " + ".join(
        f"(CASE WHEN c.content ILIKE :t{i} ESCAPE '\\' THEN 1 ELSE 0 END)" for i in range(len(terms))
    )
    where = " OR ".join(f"c.content ILIKE :t{i} ESCAPE '\\'" for i in range(len(terms)))
    sql = text(
        f"""
        SELECT c.id AS chunk_id, c.document_id AS document_id, d.title AS title,
               c.heading AS heading, c.content AS content,
               c.char_start AS char_start, c.char_end AS char_end,
               c.chunk_index AS chunk_index,
               ({matches})::float AS score
        FROM document_chunk c
        JOIN document d ON d.id = c.document_id
        WHERE d.status = 'READY' AND d.deleted = FALSE
          AND ({where})
        ORDER BY score DESC, c.id ASC
        LIMIT :limit
        """
    )
    params = {f"t{i}": f"%{_escape_like(term)}%" for i, term in enumerate(terms)}
    return sql, params


async def keyword_search(session: AsyncSession, question: str, limit: int) -> list[DocumentHit]:
    """优先 pg_search BM25；扩展/索引缺失或 0 命中时回退 jieba 分词 + ILIKE。"""
    terms = segment_for_search(question)
    if not terms:
        return []
    rows: list = []
    try:
        async with session.begin_nested():
            rows = (
                await session.execute(
                    _KEYWORD_SQL_PG_SEARCH, {"q": question, "limit": limit}
                )
            ).mappings().all()
    except Exception:  # noqa: BLE001 - 扩展/操作符/索引缺失时回退
        rows = []
    if not rows:
        sql, params = _keyword_sql(terms)
        params["limit"] = limit
        rows = (await session.execute(sql, params)).mappings().all()
    return [
        DocumentHit(
            chunk_id=row["chunk_id"],
            document_id=row["document_id"],
            title=row["title"],
            heading=row["heading"],
            content=row["content"],
            score=float(row["score"] or 0.0),
            char_start=row["char_start"],
            char_end=row["char_end"],
            chunk_index=row["chunk_index"] if row["chunk_index"] is not None else -1,
        )
        for row in rows
    ]


# ---------------- 融合 + 精排 ----------------

async def retrieve_documents_fused(session: AsyncSession, question: str) -> DocumentRetrievalResult:
    vector = await retrieve_documents(session, question)
    fusion = settings.chat.fusion
    if not fusion.enabled or not settings.chat.keyword.enabled:
        return vector
    if vector.degraded:
        return vector
    try:
        keyword_hits = await keyword_search(session, question, settings.chat.keyword.top_k)
    except Exception as exc:  # noqa: BLE001 - 少一路召回，不降级整条链路
        log.warning("keyword_channel_failed", error=str(exc))
        return vector
    if not keyword_hits:
        return vector

    fused = rrf_fuse([vector.hits, keyword_hits], fusion.rrf_k)
    candidates = fused[: max(1, fusion.candidate_limit)]
    candidates = await _apply_rerank(question, candidates)

    props = settings.chat.documents
    selected = select_documents(candidates, max(1, props.top_k), max(1, props.max_per_document))
    return DocumentRetrievalResult(
        hits=selected,
        searched=True,
        top_score=vector.top_score,
        query_embedding_ms=vector.query_embedding_ms,
        sql_ms=vector.sql_ms,
    )


async def _apply_rerank(question: str, candidates: list[DocumentHit]) -> list[DocumentHit]:
    props = settings.chat.rerank
    if not props.enabled or not candidates:
        return candidates
    client = get_rerank_client()
    if not client.is_configured():
        return candidates
    try:
        scores = await client.rerank(question, [hit.content or "" for hit in candidates], len(candidates))
    except AiException as exc:
        log.warning("rerank_failed", error_type=exc.error_type)
        return candidates
    if not scores:
        return candidates
    if not evidence_gate_keep([score for _, score in scores], props.min_score):
        log.info("evidence_gate_blocked", threshold=props.min_score)
        return []
    ranked = [
        (index, score)
        for index, score in scores
        if 0 <= index < len(candidates)
    ]
    ranked.sort(key=lambda item: item[1], reverse=True)
    result: list[DocumentHit] = []
    for index, score in ranked:
        hit = candidates[index]
        result.append(
            DocumentHit(
                chunk_id=hit.chunk_id,
                document_id=hit.document_id,
                title=hit.title,
                heading=hit.heading,
                content=hit.content,
                score=score,
                char_start=hit.char_start,
                char_end=hit.char_end,
                chunk_index=hit.chunk_index,
            )
        )
    return result


# ---------------- 并行编排 ----------------

async def retrieve(
    question: str,
    window: TimeWindow,
    keywords: list[str],
    route: QueryRoute = QueryRoute.BOTH,
) -> RetrievalBundle:
    """时间线与文档两路并行召回；任一路异常原样上抛。"""
    if route is QueryRoute.DIRECT:
        return RetrievalBundle(
            timeline=TimelineResult([], window, 0, 0, False, keywords or [], False),
            documents=DocumentRetrievalResult.not_searched(),
            timeline_ms=0,
            document_ms=0,
        )

    async def _timeline() -> tuple[TimelineResult, int]:
        started = time.perf_counter()
        async with SessionLocal() as session:
            result = await retrieve_timeline(session, window, keywords)
        return result, _elapsed_ms(started)

    async def _documents() -> tuple[DocumentRetrievalResult, int]:
        started = time.perf_counter()
        async with SessionLocal() as session:
            result = await retrieve_documents_fused(session, question)
        return result, _elapsed_ms(started)

    timeline_task = asyncio.create_task(_timeline()) if route in (QueryRoute.BOTH, QueryRoute.TIMELINE) else None
    document_task = asyncio.create_task(_documents()) if route in (QueryRoute.BOTH, QueryRoute.DOCUMENTS) else None

    timeline_result = TimelineResult([], window, 0, 0, False, keywords or [], False)
    timeline_ms = 0
    document_result = DocumentRetrievalResult.not_searched()
    document_ms = 0

    # 先 await 时间线、再 await 文档，保证同时失败时抛出时间线的异常（与串行一致）
    if timeline_task is not None:
        timeline_result, timeline_ms = await timeline_task
    if document_task is not None:
        document_result, document_ms = await document_task

    return RetrievalBundle(
        timeline=timeline_result,
        documents=document_result,
        timeline_ms=timeline_ms,
        document_ms=document_ms,
    )


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def parse_time_window(question: str) -> TimeWindow:
    return TimeWindowParser().parse(question)


def keywords_for(question: str) -> list[str]:
    return extract_keywords(question)
