"""知识图谱：两张表 + 1 跳查询 + 实体关系抽取。"""
from __future__ import annotations

import json
import re

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.context import GraphEvidenceHit
from app.core.config import settings
from app.core.logging import get_logger
from app.models.document import Document, DocumentChunk
from app.models.graph import GraphEdge, GraphNode
from app.rag.models import get_model
from app.schemas.graph import GraphStats

log = get_logger(__name__)

_EXTRACTION_PROMPT = """从下面的文本中抽取实体关系三元组，输出 JSON 数组，最多 {max_triples} 条。
每条形如 {{"source": "实体A", "relation": "关系", "target": "实体B"}}。
实体用文本里出现的名词短语，关系用简短动词或名词（如「属于」「包含」「用于」）。
只输出 JSON，不要任何解释。文本没有明确关系就输出 []。

文本：
{text}
"""


def normalize_entity(name: str) -> str:
    return re.sub(r"\s+", " ", (name or "").strip().lower())


async def stats(session: AsyncSession) -> GraphStats:
    nodes = int((await session.execute(select(func.count()).select_from(GraphNode))).scalar_one())
    edges = int((await session.execute(select(func.count()).select_from(GraphEdge))).scalar_one())
    return GraphStats(nodes=nodes, edges=edges, enabled=settings.graph.enabled)


def _question_terms(question: str) -> list[str]:
    terms: list[str] = []
    for token in re.split(r"[\s，。！？、,.!?；;：:\"'“”（）()\[\]【】/]+", question or ""):
        token = token.strip()
        if len(token) >= 2:
            terms.append(token)
    for match in re.findall(r"[A-Za-z][A-Za-z0-9+#._-]{1,40}", question or ""):
        if match not in terms:
            terms.append(match)
    return terms


async def search(session: AsyncSession, question: str, limit: int) -> tuple[list[GraphEvidenceHit], bool, bool, str | None]:
    if not settings.graph.enabled:
        return [], False, False, None
    if not question or not question.strip():
        return [], True, False, None

    nodes = (await session.execute(select(GraphNode.id, GraphNode.normalized))).all()
    normalized_question = normalize_entity(question)
    terms = [normalize_entity(term) for term in _question_terms(question)]
    matched_ids = [
        node_id
        for node_id, normalized in nodes
        if normalized and (normalized in normalized_question or normalized in terms)
    ][:200]
    if not matched_ids:
        return [], True, False, None

    source = GraphNode.__table__.alias("s")
    target = GraphNode.__table__.alias("t")
    stmt = (
        select(
            GraphEdge.id,
            source.c.name,
            GraphEdge.relation,
            target.c.name,
            GraphEdge.document_id,
            Document.title,
        )
        .join(source, source.c.id == GraphEdge.source_id)
        .join(target, target.c.id == GraphEdge.target_id)
        .join(Document, Document.id == GraphEdge.document_id)
        .where(
            (GraphEdge.source_id.in_(matched_ids)) | (GraphEdge.target_id.in_(matched_ids)),
            Document.deleted.is_(False),
        )
        .order_by(GraphEdge.weight.desc(), GraphEdge.id.asc())
        .limit(max(1, limit))
    )
    rows = (await session.execute(stmt)).all()
    hits = [
        GraphEvidenceHit(
            edge_id=row[0],
            source=row[1],
            relation=row[2],
            target=row[3],
            document_id=row[4],
            title=row[5],
        )
        for row in rows
    ]
    return hits, True, False, None


async def reindex(session: AsyncSession, limit: int) -> int:
    """给还没有图谱数据的 READY 文档抽三元组。返回处理的文档数。"""
    if not settings.graph.enabled:
        return 0
    client = get_model("chat")
    if not client.is_configured():
        return 0

    subquery = select(GraphEdge.document_id).where(GraphEdge.document_id == Document.id)
    docs = (
        (
            await session.execute(
                select(Document)
                .where(
                    Document.status == "READY",
                    Document.deleted.is_(False),
                    ~subquery.exists(),
                )
                .order_by(Document.id.asc())
                .limit(max(1, limit))
            )
        )
        .scalars()
        .all()
    )

    indexed = 0
    for document in docs:
        await reindex_document(session, document.id)
        indexed += 1
    return indexed


async def reindex_document(session: AsyncSession, document_id: int) -> bool:
    """给单篇 READY 文档抽三元组；已有边或文档不可用则跳过。"""
    if not settings.graph.enabled:
        return False
    client = get_model("chat")
    if not client.is_configured():
        return False
    document = await session.get(Document, document_id)
    if document is None or document.deleted or document.status != "READY":
        return False
    existing = (
        await session.execute(
            select(GraphEdge.id).where(GraphEdge.document_id == document_id).limit(1)
        )
    ).scalar_one_or_none()
    if existing is not None:
        return False

    chunks = (
        (
            await session.execute(
                select(DocumentChunk)
                .where(DocumentChunk.document_id == document_id)
                .order_by(DocumentChunk.chunk_index.asc())
                .limit(20)
            )
        )
        .scalars()
        .all()
    )
    if not chunks:
        return False
    text = "\n\n".join(chunk.content for chunk in chunks)[: settings.graph.extraction_max_chars]
    try:
        completion = await client.complete(
            [
                {
                    "role": "user",
                    "content": _EXTRACTION_PROMPT.format(
                        max_triples=settings.graph.max_triples_per_document, text=text
                    ),
                }
            ],
            max_tokens=1024,
            temperature=0.0,
            timeout_s=settings.graph.extraction_timeout_s,
        )
        triples = _parse_triples(completion.content)
    except Exception as exc:  # noqa: BLE001 - 单篇失败不影响其它文档
        log.warning("graph_extraction_failed", document_id=document_id, error=str(exc))
        return False
    await _store_triples(session, document_id, triples)
    return True


def _parse_triples(raw: str) -> list[tuple[str, str, str]]:
    if not raw:
        return []
    start = raw.find("[")
    end = raw.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        parsed = json.loads(raw[start:end + 1])
    except ValueError:
        return []
    result: list[tuple[str, str, str]] = []
    if not isinstance(parsed, list):
        return result
    for item in parsed:
        if not isinstance(item, dict):
            continue
        source = str(item.get("source") or "").strip()
        relation = str(item.get("relation") or "").strip()
        target = str(item.get("target") or "").strip()
        if source and relation and target and source != target:
            result.append((source[:200], relation[:100], target[:200]))
    return result


async def _store_triples(
    session: AsyncSession, document_id: int, triples: list[tuple[str, str, str]]
) -> None:
    if not triples:
        return
    cache: dict[str, GraphNode] = {}

    async def node_id(name: str) -> int:
        normalized = normalize_entity(name)
        if normalized in cache:
            return cache[normalized].id
        existing = (
            await session.execute(
                select(GraphNode).where(
                    GraphNode.document_id == document_id,
                    GraphNode.normalized == normalized,
                )
            )
        ).scalar_one_or_none()
        if existing is None:
            existing = GraphNode(
                name=name,
                normalized=normalized,
                type="CONCEPT",
                document_id=document_id,
            )
            session.add(existing)
            await session.flush()
        cache[normalized] = existing
        return existing.id

    try:
        for source, relation, target in triples:
            source_id = await node_id(source)
            target_id = await node_id(target)
            exists = (
                await session.execute(
                    select(GraphEdge.id).where(
                        GraphEdge.source_id == source_id,
                        GraphEdge.target_id == target_id,
                        GraphEdge.relation == relation,
                        GraphEdge.document_id == document_id,
                    )
                )
            ).scalar_one_or_none()
            if exists is None:
                session.add(
                    GraphEdge(
                        source_id=source_id,
                        target_id=target_id,
                        relation=relation,
                        document_id=document_id,
                    )
                )
        await session.commit()
    except Exception:
        await session.rollback()
        raise
