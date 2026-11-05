"""文档入库：上传登记 → 解析 → 切块 → 向量化 → READY；失败可重试、可对账。"""
from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.errors import BusinessException, ErrorCodes
from app.core.logging import get_logger
from app.core.response import PageResult
from app.core.tasks import spawn
from app.db.session import SessionLocal
from app.document.chunker import chunk_text
from app.document.parse import extension_of, mime_type_of, parse_document
from app.document.storage import (
    delete_file,
    read_file,
    save_file,
    save_file_from_path,
    sha256_hex,
)
from app.models.document import Document, DocumentChunk
from app.rag.models import get_embedding_client
from app.schemas.document import ChunkLocator, DocumentUploadResponse, KnowledgeDocument
from app.schemas.document import DocumentChunk as ChunkSchema

log = get_logger(__name__)

MAX_PAGE_SIZE = 100
BUSY_STATUSES = ("PENDING", "PARSING", "EMBEDDING")


def to_schema(document: Document) -> KnowledgeDocument:
    return KnowledgeDocument.model_validate(document)


def validate_name(file_name: str) -> None:
    ext = extension_of(file_name)
    if ext not in settings.allowed_extensions_set:
        raise BusinessException(
            ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
            f"不支持的文件类型，仅支持：{settings.document.allowed_extensions}",
        )


def _validate(file_name: str, size: int) -> None:
    validate_name(file_name)
    limit = settings.document.max_file_size
    if size > limit:
        limit_mb = limit / (1024 * 1024)
        raise BusinessException(ErrorCodes.PAYLOAD_TOO_LARGE, f"文件过大，最大 {limit_mb:.0f}MB")


async def _find_existing(session: AsyncSession, sha256: str) -> Document | None:
    return (
        await session.execute(
            select(Document).where(
                Document.content_sha256 == sha256,
                Document.deleted.is_(False),
            )
        )
    ).scalar_one_or_none()


async def _insert_document(
    session: AsyncSession, file_name: str, size: int, sha256: str, storage_path: str
) -> DocumentUploadResponse:
    document = Document(
        title=file_name.rsplit(".", 1)[0][:200],
        file_name=file_name[:255],
        mime_type=mime_type_of(file_name),
        file_size=size,
        storage_path=storage_path,
        content_sha256=sha256,
        status="PENDING",
        chunk_count=0,
        char_count=0,
        token_count=0,
        retry_count=0,
        chunk_truncated=False,
    )
    session.add(document)
    await session.commit()
    await session.refresh(document)
    return DocumentUploadResponse(document=to_schema(document), deduplicated=False)


async def register(
    session: AsyncSession, file_name: str, data: bytes
) -> tuple[DocumentUploadResponse, bool]:
    """返回（响应, 是否需要排队解析）。内存路径，供测试与小文件使用。"""
    _validate(file_name, len(data))
    sha256 = sha256_hex(data)
    existing = await _find_existing(session, sha256)
    if existing is not None:
        return DocumentUploadResponse(document=to_schema(existing), deduplicated=True), False
    storage_path = save_file(sha256, file_name, data)
    return await _insert_document(session, file_name, len(data), sha256, storage_path), True


async def register_spooled(
    session: AsyncSession,
    file_name: str,
    tmp_path: Path,
    size: int,
    sha256: str,
) -> tuple[DocumentUploadResponse, bool]:
    """上传主路径：文件已边读边落盘并算好 sha256，这里只做去重与登记。"""
    _validate(file_name, size)
    existing = await _find_existing(session, sha256)
    if existing is not None:
        tmp_path.unlink(missing_ok=True)
        return DocumentUploadResponse(document=to_schema(existing), deduplicated=True), False
    storage_path = save_file_from_path(sha256, file_name, tmp_path)
    return await _insert_document(session, file_name, size, sha256, storage_path), True


def start_ingest(document_id: int) -> None:
    spawn(_ingest(document_id), name=f"document_ingest:{document_id}")


async def _parse_and_chunk(file_name: str, data: bytes):
    """解析 + 切块放到线程里跑，并受 parse-timeout 约束。"""

    def work():
        title, text = parse_document(file_name, data)
        return title, text, chunk_text(text)

    try:
        return await asyncio.wait_for(
            asyncio.to_thread(work), timeout=settings.document.parse_timeout_s
        )
    except TimeoutError as exc:
        raise BusinessException(ErrorCodes.DOCUMENT_PARSE_FAILED, "文档解析超时") from exc


async def _ingest(document_id: int) -> None:
    async with SessionLocal() as session:
        document = await session.get(Document, document_id)
        if document is None or document.deleted or document.status not in BUSY_STATUSES:
            return
        try:
            document.status = "PARSING"
            document.last_progress_at = _now()
            document.error_message = None
            await session.commit()

            data = await asyncio.to_thread(read_file, document.storage_path)
            title, text, result = await _parse_and_chunk(document.file_name, data)
            document.title = title[:200] or document.title
            document.normalized_text = text
            document.char_count = result.char_count
            document.token_count = result.token_count
            document.chunk_truncated = result.truncated
            document.status = "EMBEDDING"
            document.last_progress_at = _now()
            await session.commit()

            embeddings: list[list[float]] = []
            if result.chunks:
                client = get_embedding_client()
                if not client.is_configured():
                    raise BusinessException(ErrorCodes.AI_UNAVAILABLE, "向量服务未配置，无法入库")
                embeddings = await client.embed([chunk.content for chunk in result.chunks])
                if len(embeddings) != len(result.chunks):
                    raise BusinessException(ErrorCodes.AI_UNAVAILABLE, "向量数量与分块数量不一致")

            await session.execute(
                DocumentChunk.__table__.delete().where(DocumentChunk.document_id == document_id)
            )
            for chunk, embedding in zip(result.chunks, embeddings, strict=True):
                session.add(
                    DocumentChunk(
                        document_id=document_id,
                        chunk_index=chunk.index,
                        content=chunk.content,
                        heading=chunk.heading,
                        char_start=chunk.char_start,
                        char_end=chunk.char_end,
                        char_count=chunk.char_count,
                        token_count=chunk.token_count,
                        embedding=embedding,
                        embedding_model=settings.ai.embedding.model,
                    )
                )
            document.chunk_count = len(result.chunks)
            document.status = "READY"
            document.last_progress_at = _now()
            document.error_message = None
            await session.commit()

            log.info(
                "document_ingested",
                document_id=document_id,
                chunks=len(result.chunks),
                chars=result.char_count,
            )
            if settings.graph.enabled:
                _start_graph_extraction(document_id)
        except BusinessException as exc:
            await _mark_failed(session, document, exc.message)
        except Exception as exc:  # noqa: BLE001 - 任何解析/向量异常都落到 FAILED
            log.warning("document_ingest_failed", document_id=document_id, error=str(exc))
            await _mark_failed(session, document, str(exc)[:500])


def _start_graph_extraction(document_id: int) -> None:
    async def run() -> None:
        from app.services import graph

        try:
            async with SessionLocal() as session:
                await graph.reindex_document(session, document_id)
        except Exception as exc:  # noqa: BLE001 - 图谱是派生数据，失败不影响入库
            log.warning("graph_index_after_ingest_failed", document_id=document_id, error=str(exc))

    spawn(run(), name=f"graph_extract:{document_id}")


async def _mark_failed(session: AsyncSession, document: Document, message: str) -> None:
    document.status = "FAILED"
    document.error_message = message[:500]
    document.last_progress_at = _now()
    await session.commit()


async def list_documents(
    session: AsyncSession, page: int, size: int, status: str | None
) -> PageResult[KnowledgeDocument]:
    safe_page = max(1, page)
    safe_size = max(1, min(size, MAX_PAGE_SIZE))
    conditions = [Document.deleted.is_(False)]
    if status:
        conditions.append(Document.status == status.strip().upper())
    total = int(
        (
            await session.execute(
                select(func.count()).select_from(select(Document.id).where(*conditions).subquery())
            )
        ).scalar_one()
    )
    rows = (
        (
            await session.execute(
                select(Document)
                .where(*conditions)
                .order_by(Document.created_at.desc(), Document.id.desc())
                .offset((safe_page - 1) * safe_size)
                .limit(safe_size)
            )
        )
        .scalars()
        .all()
    )
    return PageResult[KnowledgeDocument](
        items=[to_schema(row) for row in rows],
        total=total,
        page=safe_page,
        size=safe_size,
    )


async def get(session: AsyncSession, document_id: int) -> KnowledgeDocument:
    return to_schema(await must_exist(session, document_id))


async def chunks(
    session: AsyncSession, document_id: int, page: int, size: int
) -> PageResult[ChunkSchema]:
    await must_exist(session, document_id)
    safe_page = max(1, page)
    safe_size = max(1, min(size, MAX_PAGE_SIZE))
    total = int(
        (
            await session.execute(
                select(func.count()).select_from(
                    select(DocumentChunk.id)
                    .where(DocumentChunk.document_id == document_id)
                    .subquery()
                )
            )
        ).scalar_one()
    )
    rows = (
        (
            await session.execute(
                select(DocumentChunk)
                .where(DocumentChunk.document_id == document_id)
                .order_by(DocumentChunk.chunk_index.asc())
                .offset((safe_page - 1) * safe_size)
                .limit(safe_size)
            )
        )
        .scalars()
        .all()
    )
    return PageResult[ChunkSchema](
        items=[ChunkSchema.model_validate(row) for row in rows],
        total=total,
        page=safe_page,
        size=safe_size,
    )


async def locate_chunk(
    session: AsyncSession, document_id: int, chunk_id: int, size: int
) -> ChunkLocator:
    await must_exist(session, document_id)
    safe_size = max(1, min(size, MAX_PAGE_SIZE))
    chunk = (
        await session.execute(
            select(DocumentChunk).where(
                DocumentChunk.id == chunk_id,
                DocumentChunk.document_id == document_id,
            )
        )
    ).scalar_one_or_none()
    if chunk is None:
        raise BusinessException(ErrorCodes.NOT_FOUND, "文档块不存在")
    before = int(
        (
            await session.execute(
                select(func.count()).select_from(
                    select(DocumentChunk.id)
                    .where(
                        DocumentChunk.document_id == document_id,
                        DocumentChunk.chunk_index < chunk.chunk_index,
                    )
                    .subquery()
                )
            )
        ).scalar_one()
    )
    total = int(
        (
            await session.execute(
                select(func.count()).select_from(
                    select(DocumentChunk.id)
                    .where(DocumentChunk.document_id == document_id)
                    .subquery()
                )
            )
        ).scalar_one()
    )
    return ChunkLocator(
        chunk=ChunkSchema.model_validate(chunk),
        page=before // safe_size + 1,
        size=safe_size,
        total=total,
    )


async def reindex(session: AsyncSession, document_id: int) -> None:
    document = await must_exist(session, document_id)
    if document.status in BUSY_STATUSES:
        raise BusinessException(ErrorCodes.DOCUMENT_BUSY, "文档正在处理中")
    await session.execute(
        DocumentChunk.__table__.delete().where(DocumentChunk.document_id == document_id)
    )
    document.status = "PENDING"
    document.chunk_count = 0
    document.error_message = None
    document.last_progress_at = _now()
    await session.commit()
    start_ingest(document_id)


async def delete(session: AsyncSession, document_id: int) -> None:
    document = await must_exist(session, document_id)
    await session.execute(
        DocumentChunk.__table__.delete().where(DocumentChunk.document_id == document_id)
    )
    document.deleted = True
    await session.commit()
    delete_file(document.storage_path)


async def must_exist(session: AsyncSession, document_id: int) -> Document:
    document = await session.get(Document, document_id)
    if document is None or document.deleted:
        raise BusinessException(ErrorCodes.NOT_FOUND, "文档不存在")
    return document


async def reconcile_once() -> int:
    """扫卡住的任务：状态是处理中、但进度心跳超时的，重新排队。"""
    if not settings.document.reconcile.enabled:
        return 0
    stale_before = _now() - timedelta(seconds=settings.document.reconcile.stale_after_s)
    async with SessionLocal() as session:
        rows = (
            (
                await session.execute(
                    select(Document).where(
                        Document.deleted.is_(False),
                        Document.status.in_(BUSY_STATUSES),
                        Document.last_progress_at < stale_before,
                    )
                )
            )
            .scalars()
            .all()
        )
        for document in rows:
            if document.retry_count >= settings.document.reconcile.max_retries:
                document.status = "FAILED"
                document.error_message = "处理超时，重试次数已达上限"
            else:
                document.retry_count += 1
                document.status = "PENDING"
            document.last_progress_at = _now()
        if rows:
            await session.commit()
        for document in rows:
            if document.status == "PENDING":
                start_ingest(document.id)
        return len(rows)


async def reconcile_loop() -> None:
    interval = max(30.0, settings.document.reconcile.interval_s)
    while True:
        try:
            count = await reconcile_once()
            if count:
                log.info("document_reconciled", count=count)
        except Exception as exc:  # noqa: BLE001 - 对账循环不能中断
            log.warning("document_reconcile_failed", error=str(exc))
        await asyncio.sleep(interval)


def _now() -> datetime:
    return datetime.now(UTC)
