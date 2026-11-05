"""Agent 检索阶段的证据上下文：去重、编号、生成 sources 与 Prompt 证据块。"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from app.core.config import settings
from app.rag.retrieval import DocumentRetrievalResult, TimelineResult
from app.rag.timewindow import TimeWindow
from app.schemas.chat import RetrievalMeta, SourceItem, Sources, Step
from app.schemas.memory import Memory

MAX_ITEM_CHARS = 220


@dataclass
class EvidenceItem:
    kind: str  # timeline / document / memory / graph
    ref: str
    source_id: int | None
    text: str
    start_time: datetime | None = None
    end_time: datetime | None = None
    chunk_id: int | None = None
    title: str | None = None
    heading: str | None = None
    score: float | None = None
    memory_type: str | None = None
    document_id: int | None = None


@dataclass
class GraphEvidenceHit:
    """图谱检索的单条命中（避免与 ORM 耦合）。"""

    edge_id: int
    source: str
    relation: str
    target: str
    document_id: int | None = None
    title: str | None = None


@dataclass
class AgentToolContext:
    window: TimeWindow
    keywords: list[str]
    question: str
    conversation_id: int | None = None
    items: list[EvidenceItem] = field(default_factory=list)
    steps: list[Step] = field(default_factory=list)
    timeline_matched: int = 0
    timeline_retrieved: int = 0
    timeline_limit_truncated: bool = False
    timeline_fallback: bool = False
    documents_searched: bool = False
    documents_retrieved: int = 0
    documents_degraded: bool = False
    degrade_reason: str | None = None
    _keys: set[tuple] = field(default_factory=set)
    _next: dict[str, int] = field(default_factory=lambda: {"T": 0, "D": 0, "M": 0, "G": 0})

    # ---- 记录步骤 ----

    def record_step(self, tool: str, query: str, hits: int, ok: bool, ms: int, error: str | None) -> Step:
        step = Step(
            seq=len(self.steps) + 1,
            tool=tool,
            query=(query or "")[:120],
            hits=hits,
            ok=ok,
            ms=ms,
            error=error,
        )
        self.steps.append(step)
        return step

    # ---- 添加证据 ----

    def _can_add(self) -> bool:
        return len(self.items) < max(1, settings.agent.max_evidence_items)

    def _add(self, item: EvidenceItem, key: tuple) -> bool:
        if key in self._keys or not self._can_add():
            return False
        self._keys.add(key)
        self.items.append(item)
        return True

    def add_timeline(self, result: TimelineResult) -> list[EvidenceItem]:
        self.timeline_matched = max(self.timeline_matched, result.matched)
        self.timeline_retrieved = max(self.timeline_retrieved, result.retrieved)
        self.timeline_limit_truncated = self.timeline_limit_truncated or result.limit_truncated
        self.timeline_fallback = self.timeline_fallback or result.keyword_fallback
        added: list[EvidenceItem] = []
        for entry in result.items:
            key = ("timeline", entry.id)
            if key in self._keys or not self._can_add():
                continue
            self._next["T"] += 1
            item = EvidenceItem(
                kind="timeline",
                ref=f"T{self._next['T']}",
                source_id=entry.id,
                text=entry.content,
                start_time=entry.start_time,
                end_time=entry.end_time,
            )
            self._add(item, key)
            added.append(item)
        return added

    def add_documents(self, result: DocumentRetrievalResult) -> list[EvidenceItem]:
        self.documents_searched = self.documents_searched or result.searched
        self.documents_retrieved = max(self.documents_retrieved, len(result.hits))
        self.documents_degraded = self.documents_degraded or result.degraded
        if result.degrade_reason is not None:
            self.degrade_reason = result.degrade_reason.value
        added: list[EvidenceItem] = []
        for hit in result.hits:
            key = ("document", hit.chunk_id)
            if key in self._keys or not self._can_add():
                continue
            self._next["D"] += 1
            item = EvidenceItem(
                kind="document",
                ref=f"D{self._next['D']}",
                source_id=hit.document_id,
                text=hit.content,
                chunk_id=hit.chunk_id,
                title=hit.title,
                heading=hit.heading,
                score=hit.score,
                document_id=hit.document_id,
            )
            self._add(item, key)
            added.append(item)
        return added

    def add_memories(self, memories: list[Memory]) -> list[EvidenceItem]:
        added: list[EvidenceItem] = []
        for memory in memories:
            key = ("memory", memory.id)
            if key in self._keys or not self._can_add():
                continue
            self._next["M"] += 1
            item = EvidenceItem(
                kind="memory",
                ref=f"M{self._next['M']}",
                source_id=memory.id,
                text=memory.content,
                score=memory.confidence,
                memory_type=memory.type,
            )
            self._add(item, key)
            added.append(item)
        return added

    def add_graph(self, hits: list[GraphEvidenceHit]) -> list[EvidenceItem]:
        added: list[EvidenceItem] = []
        for hit in hits:
            key = ("graph", hit.edge_id)
            if key in self._keys or not self._can_add():
                continue
            self._next["G"] += 1
            item = EvidenceItem(
                kind="graph",
                ref=f"G{self._next['G']}",
                source_id=hit.edge_id,
                text=f"{hit.source} —{hit.relation}→ {hit.target}",
                title=hit.title,
                document_id=hit.document_id,
            )
            self._add(item, key)
            added.append(item)
        return added

    # ---- 输出 ----

    def counts(self) -> dict[str, int]:
        result = {"timeline": 0, "document": 0, "memory": 0, "graph": 0}
        for item in self.items:
            result[item.kind] = result.get(item.kind, 0) + 1
        return result

    def evidence_block(self, max_chars: int) -> str:
        lines: list[str] = []
        total = 0
        for item in self.items:
            prefix = f"[{item.ref}] "
            if item.kind == "timeline" and item.start_time:
                prefix += item.start_time.strftime("%Y-%m-%d %H:%M") + " "
            if item.title:
                prefix += f"《{item.title}》"
                if item.heading:
                    prefix += f"｜{item.heading}"
                prefix += " "
            text = (item.text or "").replace("\r", " ").replace("\n", " ").strip()
            line = prefix + text[:MAX_ITEM_CHARS]
            if total + len(line) + 1 > max_chars:
                break
            lines.append(line)
            total += len(line) + 1
        return "\n".join(lines)

    def sources(self) -> Sources:
        items: list[SourceItem] = []
        for item in self.items:
            items.append(
                SourceItem(
                    ref=item.ref,
                    type=item.kind,
                    id=item.source_id,
                    start_time=item.start_time,
                    end_time=item.end_time,
                    excerpt=(item.text or "")[:120],
                    chunk_id=item.chunk_id,
                    title=item.title,
                    heading=item.heading,
                    score=round(item.score, 6) if item.score is not None else None,
                    memory_type=item.memory_type,
                    document_id=item.document_id,
                )
            )
        counts = self.counts()
        meta = RetrievalMeta(
            window_start=self.window.start,
            window_end=self.window.end,
            time_basis=self.window.basis.value,
            clamped=self.window.clamped,
            matched=self.timeline_matched,
            retrieved=self.timeline_retrieved,
            included=counts["timeline"],
            limit_truncated=self.timeline_limit_truncated,
            budget_truncated=False,
            keywords=self.keywords,
            keyword_fallback=self.timeline_fallback,
            document_searched=self.documents_searched,
            documents_retrieved=self.documents_retrieved,
            document_included=counts["document"],
            total_included=len(items),
            documents_degraded=self.documents_degraded,
            degrade_reason=self.degrade_reason,
            memory_included=counts["memory"],
            graph_included=counts["graph"],
        )
        return Sources(items=items, retrieval=meta)
