"""文档接口。"""
from __future__ import annotations

import hashlib
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, Query, UploadFile
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.errors import BusinessException, ErrorCodes
from app.core.response import ApiResponse
from app.db.session import get_session
from app.services import document as document_service

router = APIRouter(prefix="/api/documents", tags=["document"])

_UPLOAD_CHUNK_SIZE = 1024 * 1024


async def _spool_upload(file: UploadFile, file_name: str) -> tuple[Path, int, str]:
    """边读边落盘并计算大小与 sha256：大文件不会先整块进内存。"""
    document_service.validate_name(file_name)
    limit = settings.document.max_file_size
    tmp_dir = Path(settings.document.storage_dir) / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    tmp_path = tmp_dir / f"{uuid.uuid4().hex}.upload"
    digest = hashlib.sha256()
    total = 0
    try:
        with tmp_path.open("wb") as out:
            while True:
                chunk = await file.read(_UPLOAD_CHUNK_SIZE)
                if not chunk:
                    break
                total += len(chunk)
                if total > limit:
                    limit_mb = limit // (1024 * 1024)
                    raise BusinessException(
                        ErrorCodes.PAYLOAD_TOO_LARGE, f"文件过大，最大 {limit_mb}MB"
                    )
                digest.update(chunk)
                out.write(chunk)
    except Exception:
        tmp_path.unlink(missing_ok=True)
        raise
    return tmp_path, total, digest.hexdigest()


@router.post("")
async def upload(file: UploadFile = File(...), session: AsyncSession = Depends(get_session)):
    file_name = file.filename or "upload.txt"
    tmp_path, size, sha256 = await _spool_upload(file, file_name)
    try:
        response, send_parse = await document_service.register_spooled(
            session, file_name, tmp_path, size, sha256
        )
    except Exception:
        tmp_path.unlink(missing_ok=True)
        raise
    if send_parse:
        document_service.start_ingest(response.document.id)
    status_code = 200 if response.deduplicated else 202
    return JSONResponse(
        status_code=status_code,
        content=ApiResponse.success(response).model_dump(mode="json", by_alias=True),
    )


@router.get("")
async def list_documents(
    page: int = Query(default=1),
    size: int = Query(default=20),
    status: str | None = Query(default=None),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(await document_service.list_documents(session, page, size, status))


@router.get("/{document_id}")
async def get(document_id: int, session: AsyncSession = Depends(get_session)):
    return ApiResponse.success(await document_service.get(session, document_id))


@router.get("/{document_id}/chunks")
async def chunks(
    document_id: int,
    page: int = Query(default=1),
    size: int = Query(default=20),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(await document_service.chunks(session, document_id, page, size))


@router.get("/{document_id}/chunks/{chunk_id}")
async def locate_chunk(
    document_id: int,
    chunk_id: int,
    size: int = Query(default=20),
    session: AsyncSession = Depends(get_session),
):
    return ApiResponse.success(
        await document_service.locate_chunk(session, document_id, chunk_id, size)
    )


@router.post("/{document_id}/reindex")
async def reindex(document_id: int, session: AsyncSession = Depends(get_session)):
    await document_service.reindex(session, document_id)
    return JSONResponse(
        status_code=202,
        content=ApiResponse.success().model_dump(mode="json", by_alias=True),
    )


@router.delete("/{document_id}")
async def delete(document_id: int, session: AsyncSession = Depends(get_session)):
    await document_service.delete(session, document_id)
    return ApiResponse.success()
