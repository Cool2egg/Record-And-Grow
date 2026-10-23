"""自建指标：全部是集合运算，不调模型、不花钱。

口径：
- Hit@K / Recall / MRR **按来源类型分开算**（T 与 D 各自排序），两类证据混排没有意义；
- 只统计 requires_* 的样本，负样本不参与召回指标（否则"本该拒答"的样本会污染分母）；
- 引用正确率不需要 judge：答案里的 [D1] 与 sources 的 ref 一一对应；
- 行为判定分三类：direct 必须无来源、refuse 必须明确说没找到、answer 至少要有一条相关来源。
"""

from __future__ import annotations

import math
import re
from statistics import fmean
from typing import Any

from .schema import Observation, Sample

K = 5
REFUSAL_MARKERS = ("没有找到", "未找到", "没有相关", "没有记录", "找不到")
CITATION = re.compile(r"\[([TD])(\d+)]")


def _of_type(observation: Observation, source_type: str) -> list[dict[str, Any]]:
    return [source for source in observation.sources if source.get("type") == source_type]


def _doc_relevant(sample: Sample, source: dict[str, Any]) -> bool:
    title = source.get("title") or ""
    if any(expected in title for expected in sample.expected_doc_titles):
        return True
    excerpt = source.get("excerpt") or ""
    return any(phrase in excerpt for phrase in sample.expected_doc_phrases)


def _timeline_relevant(sample: Sample, source: dict[str, Any]) -> bool:
    excerpt = source.get("excerpt") or ""
    return any(phrase in excerpt for phrase in sample.expected_timeline_phrases)


def _hit(flags: list[bool], k: int) -> bool:
    return any(flags[:k])


def _recall(flags: list[bool], expected: int) -> float:
    return min(1.0, sum(1 for flag in flags if flag) / max(1, expected))


def _mrr(flags: list[bool], k: int) -> float:
    for index, flag in enumerate(flags[:k]):
        if flag:
            return 1.0 / (index + 1)
    return 0.0


def _citation_stats(
    observation: Observation, doc_flags: list[bool], timeline_flags: list[bool]
) -> tuple[float | None, float | None]:
    """返回 (可解析率, 相关率)。

    两件事要分开：**引用了不存在的编号**是硬错误（模型编造 [T9]），
    **引用了存在但非 gold 的证据**只是证据不够准（时间线里每条都是真事实，
    引用别的记录不算错），后者不能当失败判据。
    """
    answer = observation.answer or ""
    total = 0
    resolved = 0
    relevant = 0
    for match in CITATION.finditer(answer):
        ref = match.group(1) + match.group(2)
        total += 1
        source = next((s for s in observation.sources if s.get("ref") == ref), None)
        if source is None:
            continue
        resolved += 1
        if source.get("type") == "document":
            index = _of_type(observation, "document").index(source)
            relevant += 1 if index < len(doc_flags) and doc_flags[index] else 0
        else:
            index = _of_type(observation, "timeline").index(source)
            relevant += 1 if index < len(timeline_flags) and timeline_flags[index] else 0
    if total == 0:
        return None, None
    return resolved / total, relevant / total


def score(sample: Sample, observation: Observation) -> dict[str, Any]:
    docs = _of_type(observation, "document")
    timeline = _of_type(observation, "timeline")
    doc_flags = [_doc_relevant(sample, source) for source in docs]
    timeline_flags = [_timeline_relevant(sample, source) for source in timeline]

    expected_docs = max(1, len(sample.expected_doc_titles)) if sample.requires_document else 1
    behavior = (sample.behavior or "answer").lower()

    # 「该检索」不等于「该有命中」：无答案的负样本也要求检索，但没有期望证据，
    # 它的判定归行为指标（必须拒答），不能算进召回率，否则把 0 分算成系统缺陷。
    doc_applicable = sample.requires_document and bool(
        sample.expected_doc_titles or sample.expected_doc_phrases
    )
    timeline_applicable = sample.requires_timeline and bool(sample.expected_timeline_phrases)

    if behavior == "direct":
        behavior_ok = not observation.sources
    elif behavior == "refuse":
        behavior_ok = any(marker in (observation.answer or "") for marker in REFUSAL_MARKERS)
    else:
        behavior_ok = any(doc_flags) or any(timeline_flags)

    over_retrieved = (not sample.requires_document and bool(docs)) or (
        not sample.requires_timeline and bool(timeline)
    )

    # 时间线通道**没有相关性排序**：它按 start_time 倒序，返回的是"窗口内最近的 N 条"。
    # 所以对它取 top-5 没有意义（那只是"最近 5 条"），窗口内所有返回的条目都要看；
    # 文档通道才用 Hit@5。
    tl_k = max(1, len(timeline_flags))
    citation_resolved, citation_relevant = _citation_stats(observation, doc_flags, timeline_flags)

    return {
        "id": sample.id,
        "category": sample.category,
        "difficulty": sample.difficulty,
        "doc_hit": _hit(doc_flags, K) if doc_applicable else None,
        "doc_recall": _recall(doc_flags, expected_docs) if doc_applicable else None,
        "doc_mrr": _mrr(doc_flags, K) if doc_applicable else None,
        "tl_hit": _hit(timeline_flags, tl_k) if timeline_applicable else None,
        "tl_recall": _recall(timeline_flags, 1) if timeline_applicable else None,
        "tl_mrr": _mrr(timeline_flags, tl_k) if timeline_applicable else None,
        "citation_resolved": citation_resolved,
        "citation_precision": citation_relevant,
        "behavior_ok": behavior_ok,
        "over_retrieved": over_retrieved,
        "ttft_ms": observation.ttft_ms,
        "total_ms": observation.total_ms,
        "error": observation.error,
    }


def _mean(values: list[float]) -> float | None:
    clean = [value for value in values if value is not None and not math.isnan(value)]
    return round(fmean(clean), 4) if clean else None


def _rate(rows: list[dict[str, Any]], key: str) -> float | None:
    if not rows:
        return None
    return round(sum(1 for row in rows if row.get(key)) / len(rows), 4)


def _summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    hits = {
        key: _mean([float(row[key]) for row in rows if row.get(key) is not None])
        for key in ("doc_hit", "doc_recall", "doc_mrr", "tl_hit", "tl_recall", "tl_mrr")
    }
    ttft = [row["ttft_ms"] for row in rows if (row.get("ttft_ms") or -1) > 0]
    return {
        "count": len(rows),
        **{key: (None if value is None else value) for key, value in hits.items()},
        "citation_resolved": _mean([row["citation_resolved"] for row in rows]),
        "citation_precision": _mean([row["citation_precision"] for row in rows]),
        "behavior_ok": _rate(rows, "behavior_ok"),
        "over_retrieval": _rate(rows, "over_retrieved"),
        "ttft_mean_ms": _mean([float(value) for value in ttft]),
        "total_mean_ms": _mean([float(row["total_ms"]) for row in rows if row.get("total_ms")]),
        "errors": sum(1 for row in rows if row.get("error")),
    }


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """整体 + 按类别切片。切片是定位问题的主要手段：overall 会掩盖某一类全错。"""
    overall = _summarize(rows)
    by_category: dict[str, Any] = {}
    for category in sorted({row["category"] for row in rows}):
        by_category[category] = _summarize([row for row in rows if row["category"] == category])
    return {"overall": overall, "by_category": by_category}


def failures(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """失败判定：任一条件命中即失败，可同时命中多条（多标签才能看出因果链）。"""
    failed = []
    for row in rows:
        reasons = []
        if row.get("doc_hit") is False:
            reasons.append("doc_miss")
        if row.get("tl_hit") is False:
            reasons.append("timeline_miss")
        if row.get("behavior_ok") is False:
            reasons.append("behavior_wrong")
        # over_retrieved 不进失败清单：路由没开时两路必查，这是结构性现象，
        # 不是某条样本的错误。它仍然作为独立指标输出。
        if row.get("citation_resolved") is not None and row["citation_resolved"] < 1.0:
            reasons.append(f"dangling_citation({row['citation_resolved']:.2f})")
        if row.get("error"):
            reasons.append(f"error:{row['error']}")
        if reasons:
            failed.append({**row, "failure_reasons": reasons})
    return failed
