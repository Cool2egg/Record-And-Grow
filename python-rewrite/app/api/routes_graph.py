"""知识图谱接口。"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.response import ApiResponse
from app.db.session import get_session
from app.schemas.graph import GraphHit, GraphSearchResponse
from app.services import graph as graph_service

router = APIRouter(prefix="/api/graph", tags=["graph"])


@router.get("/search")
async def search(
    q: str = Query(),
    limit: int | None = Query(default=None),
    session: AsyncSession = Depends(get_session),
):
    hits, searched, degraded, reason = await graph_service.search(
        session, q, limit if limit and limit > 0 else 20
    )
    if limit and limit > 0:
        hits = hits[:limit]
    return ApiResponse.success(
        GraphSearchResponse(
            hits=[
                GraphHit(
                    edge_id=hit.edge_id,
                    source=hit.source,
                    relation=hit.relation,
                    target=hit.target,
                    document_id=hit.document_id,
                    title=hit.title,
                )
                for hit in hits
            ],
            searched=searched,
            degraded=degraded,
            degrade_reason=reason,
        )
    )


@router.get("/stats")
async def stats(session: AsyncSession = Depends(get_session)):
    return ApiResponse.success(await graph_service.stats(session))


@router.post("/reindex")
async def reindex(
    limit: int = Query(default=5), session: AsyncSession = Depends(get_session)
):
    indexed = await graph_service.reindex(session, limit)
    return ApiResponse.success({"indexed": indexed})
