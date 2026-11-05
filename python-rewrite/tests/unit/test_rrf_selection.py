"""RRF 融合与相邻块合并。"""
from __future__ import annotations

from app.rag.rrf import DocumentHit, rrf_fuse
from app.rag.selection import select_documents


def hit(chunk_id: int, document_id: int = 1, chunk_index: int = 0, score: float = 0.5) -> DocumentHit:
    return DocumentHit(
        chunk_id=chunk_id,
        document_id=document_id,
        title="t",
        heading=None,
        content=f"content-{chunk_id}",
        score=score,
        char_start=chunk_index * 10,
        char_end=chunk_index * 10 + 10,
        chunk_index=chunk_index,
    )


def test_rrf_prefers_consistently_ranked_items():
    channel_a = [hit(1), hit(2), hit(3)]
    channel_b = [hit(2), hit(4)]
    fused = rrf_fuse([channel_a, channel_b], k=20)
    assert fused[0].chunk_id == 2
    # score 严格按名次计算
    scores = {item.chunk_id: item.score for item in fused}
    assert abs(scores[2] - (1 / 22 + 1 / 21)) < 1e-9
    assert abs(scores[1] - 1 / 21) < 1e-9


def test_rrf_ignores_empty_channel():
    fused = rrf_fuse([[], [hit(7)]], k=60)
    assert [item.chunk_id for item in fused] == [7]


def test_selection_merges_adjacent_chunks():
    candidates = [hit(10, chunk_index=2), hit(11, chunk_index=3), hit(12, chunk_index=5)]
    selected = select_documents(candidates, top_k=8, max_per_document=2)
    assert len(selected) == 2
    assert selected[0].chunk_id == 10
    assert selected[0].content == "content-10\ncontent-11"
    assert selected[1].chunk_id == 12


def test_selection_respects_top_k_and_quota():
    candidates = [hit(1, document_id=1, chunk_index=0), hit(2, document_id=2, chunk_index=0),
                  hit(3, document_id=1, chunk_index=10), hit(4, document_id=2, chunk_index=10)]
    selected = select_documents(candidates, top_k=2, max_per_document=1)
    assert len(selected) == 2
    assert {item.document_id for item in selected} == {1, 2}
