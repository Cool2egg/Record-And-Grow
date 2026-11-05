"""倒数排名融合（RRF）：score(chunk) = Σ 1 / (k + rank + 1)。"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class DocumentHit:
    chunk_id: int
    document_id: int
    title: str | None
    heading: str | None
    content: str
    score: float
    char_start: int | None
    char_end: int | None
    chunk_index: int = -1


def rrf_fuse(channels: list[list[DocumentHit]], k: int) -> list[DocumentHit]:
    safe_k = max(1, k)
    scores: dict[int, float] = {}
    first_seen: dict[int, DocumentHit] = {}

    for channel in channels:
        if not channel:
            continue
        for rank, hit in enumerate(channel):
            if hit is None or hit.chunk_id is None:
                continue
            scores[hit.chunk_id] = scores.get(hit.chunk_id, 0.0) + 1.0 / (safe_k + rank + 1)
            first_seen.setdefault(hit.chunk_id, hit)

    fused = [
        DocumentHit(
            chunk_id=hit.chunk_id,
            document_id=hit.document_id,
            title=hit.title,
            heading=hit.heading,
            content=hit.content,
            score=scores.get(hit.chunk_id, 0.0),
            char_start=hit.char_start,
            char_end=hit.char_end,
            chunk_index=hit.chunk_index,
        )
        for hit in first_seen.values()
    ]
    fused.sort(key=lambda h: h.score, reverse=True)
    return fused
