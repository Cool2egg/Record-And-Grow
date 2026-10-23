"""评估集与运行记录的数据契约。

评估集是**数据资产**：一行一条 JSONL，字段名固定，任何语言都能读。
这里的 dataclass 只是 Python 侧的映射，改字段要先改数据再改这里。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Sample:
    """一条评估样本。证据用自然语言锚点，不用数据库 id（id 会随分块参数变化）。"""

    id: str
    category: str  # TIMELINE / DOCUMENT / MIXED / NEGATIVE
    difficulty: str
    question: str
    behavior: str  # answer / refuse / direct
    ground_truth: str
    requires_timeline: bool = False
    requires_document: bool = False
    trap: str | None = None
    time_window_start: str | None = None
    time_window_end: str | None = None
    expected_timeline_phrases: tuple[str, ...] = ()
    expected_doc_titles: tuple[str, ...] = ()
    expected_doc_phrases: tuple[str, ...] = ()

    @property
    def retrievable(self) -> bool:
        return self.requires_timeline or self.requires_document

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "Sample":
        return cls(
            id=raw["id"],
            category=raw["category"],
            difficulty=raw.get("difficulty", "medium"),
            question=raw["question"],
            behavior=raw.get("behavior", "answer"),
            ground_truth=raw.get("groundTruth", ""),
            requires_timeline=bool(raw.get("requiresTimeline", False)),
            requires_document=bool(raw.get("requiresDocument", False)),
            trap=raw.get("trap"),
            time_window_start=raw.get("timeWindowStart"),
            time_window_end=raw.get("timeWindowEnd"),
            expected_timeline_phrases=tuple(raw.get("expectedTimelinePhrases") or ()),
            expected_doc_titles=tuple(raw.get("expectedDocTitles") or ()),
            expected_doc_phrases=tuple(raw.get("expectedDocPhrases") or ()),
        )


@dataclass
class Observation:
    """一条样本的实际运行结果：只存"系统输出了什么"，不含任何判定。"""

    id: str
    question: str
    answer: str
    sources: list[dict[str, Any]]
    http_status: int
    total_ms: int
    ttft_ms: int = -1
    route: str | None = None
    error: str | None = None
    retrieval: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "question": self.question,
            "answer": self.answer,
            "sources": self.sources,
            "http_status": self.http_status,
            "total_ms": self.total_ms,
            "ttft_ms": self.ttft_ms,
            "route": self.route,
            "error": self.error,
            "retrieval": self.retrieval,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "Observation":
        return cls(
            id=raw["id"],
            question=raw.get("question", ""),
            answer=raw.get("answer", ""),
            sources=raw.get("sources") or [],
            http_status=raw.get("http_status", 0),
            total_ms=raw.get("total_ms", 0),
            ttft_ms=raw.get("ttft_ms", -1),
            route=raw.get("route"),
            error=raw.get("error"),
            retrieval=raw.get("retrieval"),
        )


def load_samples(path: str | Path) -> list[Sample]:
    """读 JSONL 评估集；空行与以 # 开头的注释行会被跳过。"""
    samples: list[Sample] = []
    for number, line in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        try:
            samples.append(Sample.from_dict(json.loads(stripped)))
        except (KeyError, json.JSONDecodeError) as exc:
            raise ValueError(f"{path}:{number} 解析失败: {exc}") from exc
    ids = [sample.id for sample in samples]
    if len(ids) != len(set(ids)):
        raise ValueError(f"{path} 存在重复 id")
    return samples


def load_observations(path: str | Path) -> list[Observation]:
    observations: list[Observation] = []
    for number, line in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), start=1):
        stripped = line.strip()
        if not stripped:
            continue
        try:
            observations.append(Observation.from_dict(json.loads(stripped)))
        except (KeyError, json.JSONDecodeError) as exc:
            raise ValueError(f"{path}:{number} 解析失败: {exc}") from exc
    return observations
