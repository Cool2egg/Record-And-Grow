"""报告：Markdown 一页纸 + 逐样本 CSV + 失败清单。全部纯文本，不依赖前端或图表库。"""

from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Any

METRIC_LABELS = [
    ("doc_hit", "Doc Hit@5"),
    ("doc_recall", "Doc Recall@5"),
    ("doc_mrr", "Doc MRR"),
    ("tl_hit", "Timeline Hit@5"),
    ("tl_recall", "Timeline Recall@5"),
    ("tl_mrr", "Timeline MRR"),
    ("citation_resolved", "引用可解析率"),
    ("citation_precision", "引用相关率"),
    ("behavior_ok", "行为正确率"),
    ("over_retrieval", "过召率"),
]


def _fmt(value: Any) -> str:
    if value is None:
        return "—"
    if isinstance(value, bool):
        return "1.000" if value else "0.000"
    if isinstance(value, float):
        return f"{value:.3f}"
    return str(value)


def _table(summary: dict[str, Any]) -> list[str]:
    lines = ["| 指标 | 值 |", "|---|---|"]
    for key, label in METRIC_LABELS:
        lines.append(f"| {label} | {_fmt(summary.get(key))} |")
    for key, label in (("ttft_mean_ms", "首字均值(ms)"), ("total_mean_ms", "端到端均值(ms)"), ("count", "样本数")):
        lines.append(f"| {label} | {_fmt(summary.get(key))} |")
    return lines


def write_markdown(aggregate: dict[str, Any], failures: list[dict[str, Any]], path: str | Path) -> Path:
    overall = aggregate["overall"]
    lines = ["# 评测报告", "", "## 总览", ""]
    lines.extend(_table(overall))
    lines.extend(["", "## 分类切片", "", "| 类别 | 样本 | Doc Hit@5 | TL Hit@5 | 引用正确率 | 行为正确率 | 过召率 |", "|---|---|---|---|---|---|---|"])
    for category, summary in aggregate["by_category"].items():
        lines.append(
            "| {cat} | {count} | {doc} | {tl} | {cite} | {behavior} | {over} |".format(
                cat=category,
                count=summary.get("count"),
                doc=_fmt(summary.get("doc_hit")),
                tl=_fmt(summary.get("tl_hit")),
                cite=_fmt(summary.get("citation_precision")),
                behavior=_fmt(summary.get("behavior_ok")),
                over=_fmt(summary.get("over_retrieval")),
            )
        )

    lines.extend(["", f"## 失败样本（{len(failures)} 条）", ""])
    if failures:
        lines.append("| 样本 | 类别 | 失败原因 |")
        lines.append("|---|---|---|")
        for row in failures:
            lines.append(f"| {row['id']} | {row['category']} | {', '.join(row['failure_reasons'])} |")
    else:
        lines.append("本次没有失败样本。")

    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out


def write_csv(rows: list[dict[str, Any]], path: str | Path) -> Path:
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    columns = [
        "id", "category", "difficulty", "doc_hit", "doc_recall", "doc_mrr",
        "tl_hit", "tl_recall", "tl_mrr", "citation_resolved", "citation_precision", "behavior_ok",
        "over_retrieved", "ttft_ms", "total_ms", "error",
    ]
    with out.open("w", encoding="utf-8-sig", newline="") as sink:
        writer = csv.DictWriter(sink, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    return out


def write_json(payload: dict[str, Any], path: str | Path) -> Path:
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return out
