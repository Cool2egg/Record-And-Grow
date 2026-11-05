"""知识图谱。"""
from __future__ import annotations

from app.schemas.base import BaseSchema


class GraphHit(BaseSchema):
    edge_id: int
    source: str
    relation: str
    target: str
    document_id: int | None = None
    title: str | None = None


class GraphSearchResponse(BaseSchema):
    hits: list[GraphHit]
    searched: bool
    degraded: bool
    degrade_reason: str | None = None


class GraphStats(BaseSchema):
    nodes: int
    edges: int
    enabled: bool
