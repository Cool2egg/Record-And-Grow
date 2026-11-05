"""证据闸门：整批最高精排分低于阈值时整批丢弃。没有精排分时放行。"""
from __future__ import annotations


def evidence_gate_keep(rerank_scores: list[float] | None, min_score: float) -> bool:
    if not rerank_scores:
        return True
    values = [s for s in rerank_scores if s is not None]
    if not values:
        return True
    return max(values) >= min_score
