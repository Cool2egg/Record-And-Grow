"""切块：按段落 + 长度上限 + 重叠；Markdown 标题作为面包屑。"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from app.core.config import settings

_HEADING = re.compile(r"^(#{1,6})\s+(.*)$")


def _make_encoder():
    try:
        import tiktoken

        return tiktoken.get_encoding("cl100k_base")
    except Exception:  # noqa: BLE001 - 无网络/未安装时退回字符估算
        return None


_ENCODER = _make_encoder()


def count_tokens(text: str) -> int:
    if not text:
        return 0
    if _ENCODER is not None:
        try:
            return len(_ENCODER.encode(text))
        except Exception:  # noqa: BLE001
            pass
    # 兜底：中文约 1 字 1 token，英文约 4 字符 1 token，取折中
    return max(1, int(len(text) / 1.6) + 1)


@dataclass
class Chunk:
    index: int
    content: str
    heading: str | None
    char_start: int
    char_end: int
    char_count: int
    token_count: int


@dataclass
class ChunkResult:
    chunks: list[Chunk] = field(default_factory=list)
    truncated: bool = False
    token_count: int = 0
    char_count: int = 0


@dataclass
class _Block:
    start: int
    text: str
    heading: str | None


def _split_blocks(text: str) -> list[_Block]:
    """按空行切块，同时跟踪 Markdown 标题面包屑。"""
    blocks: list[_Block] = []
    breadcrumbs: list[str] = []
    offset = 0
    current_start: int | None = None
    current_lines: list[str] = []
    current_heading: str | None = None

    def flush() -> None:
        nonlocal current_start, current_lines
        if current_lines and current_start is not None:
            joined = "\n".join(current_lines).strip()
            if joined:
                blocks.append(_Block(current_start, joined, current_heading))
        current_start = None
        current_lines = []

    for line in text.split("\n"):
        stripped = line.strip()
        heading_match = _HEADING.match(stripped) if stripped else None
        if heading_match:
            flush()
            level = len(heading_match.group(1))
            title = heading_match.group(2).strip()
            breadcrumbs = breadcrumbs[: level - 1]
            while len(breadcrumbs) < level - 1:
                breadcrumbs.append("")
            breadcrumbs.append(title)
            current_heading = " / ".join(b for b in breadcrumbs if b) or title
            offset += len(line) + 1
            continue
        if not stripped:
            flush()
            offset += len(line) + 1
            continue
        if current_start is None:
            current_start = offset
            current_heading = " / ".join(b for b in breadcrumbs if b) or None
        current_lines.append(line)
        offset += len(line) + 1
    flush()
    return blocks


def _split_long_text(text: str, max_tokens: int) -> list[str]:
    """单块超长时先按句切；仍超长按字符硬切。"""
    if count_tokens(text) <= max_tokens:
        return [text]
    sentences = re.split(r"(?<=[。！？!?；;])\s*", text)
    pieces: list[str] = []
    buffer = ""
    for sentence in sentences:
        if not sentence:
            continue
        candidate = buffer + sentence
        if buffer and count_tokens(candidate) > max_tokens:
            pieces.append(buffer)
            buffer = sentence
        else:
            buffer = candidate
    if buffer:
        pieces.append(buffer)

    result: list[str] = []
    for piece in pieces:
        if count_tokens(piece) <= max_tokens:
            result.append(piece)
            continue
        # 硬切：按比例估算每段字符数，逐段收缩直到满足
        approx = max(1, int(len(piece) * max_tokens / max(1, count_tokens(piece))))
        cursor = 0
        while cursor < len(piece):
            end = min(len(piece), cursor + approx)
            while end > cursor + 1 and count_tokens(piece[cursor:end]) > max_tokens:
                end -= max(1, (end - cursor) // 10)
            result.append(piece[cursor:end])
            cursor = end
    return result


def chunk_text(
    text: str,
    max_tokens: int | None = None,
    overlap_tokens: int | None = None,
    min_tokens: int | None = None,
    max_chunks: int | None = None,
) -> ChunkResult:
    cfg = settings.document.chunk
    max_tokens = max_tokens or cfg.max_tokens
    overlap_tokens = overlap_tokens if overlap_tokens is not None else cfg.overlap_tokens
    min_tokens = min_tokens if min_tokens is not None else cfg.min_tokens
    max_chunks = max_chunks or cfg.max_chunks

    max_tokens = max(20, max_tokens)
    overlap_tokens = max(0, min(overlap_tokens, max_tokens - 1))
    min_tokens = max(1, min(min_tokens, max_tokens))

    blocks = _split_blocks(text)
    pieces: list[tuple[int, str, str | None]] = []
    for block in blocks:
        for piece in _split_long_text(block.text, max_tokens):
            pieces.append((block.start, piece, block.heading))

    chunks: list[Chunk] = []
    current: list[tuple[int, str, str | None]] = []
    current_tokens = 0

    def flush(keep_overlap: bool = True) -> None:
        nonlocal current, current_tokens
        if not current:
            return
        content = "\n\n".join(piece for _, piece, _ in current).strip()
        if not content:
            current = []
            current_tokens = 0
            return
        start = min(offset for offset, _, _ in current)
        end = max(offset + len(piece_text) for offset, piece_text, _ in current)
        heading = next((h for _, _, h in current if h), None)
        chunks.append(
            Chunk(
                index=len(chunks),
                content=content,
                heading=heading,
                char_start=start,
                char_end=end,
                char_count=end - start,
                token_count=count_tokens(content),
            )
        )
        # 重叠：保留尾部若干片段
        overlap: list[tuple[int, str, str | None]] = []
        budget = overlap_tokens
        if keep_overlap:
            for item in reversed(current):
                token_count = count_tokens(item[1])
                if token_count > budget:
                    break
                overlap.insert(0, item)
                budget -= token_count
        current = overlap
        current_tokens = sum(count_tokens(item[1]) for item in overlap)

    for item in pieces:
        # 标题变化处断开：面包屑必须与块内容对应，不跨章节合并
        if current and item[2] is not None and item[2] != current[0][2]:
            flush(keep_overlap=False)
        token_count = count_tokens(item[1])
        if current and current_tokens + token_count > max_tokens:
            flush()
        # flush 后仍可能超（单块接近上限 + 重叠），再冲一次
        if current and current_tokens + token_count > max_tokens:
            flush()
        current.append(item)
        current_tokens += token_count
    flush()

    # 小尾巴并入上一块
    if len(chunks) >= 2 and chunks[-1].token_count < min_tokens:
        tail = chunks.pop()
        prev = chunks[-1]
        combined = f"{prev.content}\n\n{tail.content}"
        if count_tokens(combined) <= max_tokens:
            chunks[-1] = Chunk(
                index=prev.index,
                content=combined,
                heading=prev.heading,
                char_start=min(prev.char_start, tail.char_start),
                char_end=max(prev.char_end, tail.char_end),
                char_count=max(prev.char_end, tail.char_end) - min(prev.char_start, tail.char_start),
                token_count=count_tokens(combined),
            )
        else:
            chunks.append(tail)

    truncated = len(chunks) > max_chunks
    if truncated:
        chunks = chunks[:max_chunks]
    for i, chunk in enumerate(chunks):
        chunk.index = i

    return ChunkResult(
        chunks=chunks,
        truncated=truncated,
        token_count=count_tokens(text),
        char_count=len(text),
    )
