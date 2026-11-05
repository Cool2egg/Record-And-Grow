"""从排序好的候选里选最终结果：相邻块合并成段落 + 每文档配额 + topK 截断。"""
from __future__ import annotations

from app.rag.rrf import DocumentHit


class _Paragraph:
    def __init__(self, anchor: DocumentHit) -> None:
        self.anchor = anchor
        self.parts: list[tuple[int, str]] = [(anchor.chunk_index, anchor.content or "")]
        self.char_start = anchor.char_start
        self.char_end = anchor.char_end
        self.min_chunk_index = anchor.chunk_index
        self.max_chunk_index = anchor.chunk_index

    def adjacent_to(self, other: DocumentHit) -> bool:
        return other.chunk_index == self.min_chunk_index - 1 or other.chunk_index == self.max_chunk_index + 1

    def absorb(self, other: DocumentHit) -> None:
        self.min_chunk_index = min(self.min_chunk_index, other.chunk_index)
        self.max_chunk_index = max(self.max_chunk_index, other.chunk_index)
        self.char_start = _min(self.char_start, other.char_start)
        self.char_end = _max(self.char_end, other.char_end)
        self.parts.append((other.chunk_index, other.content or ""))
        self.parts.sort(key=lambda p: p[0])

    def absorb_paragraph(self, other: _Paragraph) -> None:
        self.min_chunk_index = min(self.min_chunk_index, other.min_chunk_index)
        self.max_chunk_index = max(self.max_chunk_index, other.max_chunk_index)
        self.char_start = _min(self.char_start, other.char_start)
        self.char_end = _max(self.char_end, other.char_end)
        self.parts.extend(other.parts)
        self.parts.sort(key=lambda p: p[0])

    def to_hit(self) -> DocumentHit:
        content = "\n".join(text for _, text in self.parts if text)
        return DocumentHit(
            chunk_id=self.anchor.chunk_id,
            document_id=self.anchor.document_id,
            title=self.anchor.title,
            heading=self.anchor.heading,
            content=content,
            score=self.anchor.score,
            char_start=self.char_start,
            char_end=self.char_end,
            chunk_index=self.min_chunk_index,
        )


def _min(a: int | None, b: int | None) -> int | None:
    if a is None:
        return b
    if b is None:
        return a
    return min(a, b)


def _max(a: int | None, b: int | None) -> int | None:
    if a is None:
        return b
    if b is None:
        return a
    return max(a, b)


def select_documents(candidates: list[DocumentHit], top_k: int, max_per_document: int) -> list[DocumentHit]:
    limit = max(1, top_k)
    per_document_limit = max(1, max_per_document)

    by_document: dict[int, list[_Paragraph]] = {}
    paragraphs: list[_Paragraph] = []

    for candidate in candidates:
        if candidate is None:
            continue
        document_paragraphs = by_document.setdefault(candidate.document_id, [])

        adjacent = [p for p in document_paragraphs if p.adjacent_to(candidate)]
        if adjacent:
            target = adjacent[0]
            target.absorb(candidate)
            for bridged in adjacent[1:]:
                target.absorb_paragraph(bridged)
                document_paragraphs.remove(bridged)
                paragraphs.remove(bridged)
            continue

        if len(paragraphs) >= limit:
            break
        if len(document_paragraphs) >= per_document_limit:
            continue
        paragraph = _Paragraph(candidate)
        document_paragraphs.append(paragraph)
        paragraphs.append(paragraph)

    return [p.to_hit() for p in paragraphs]
