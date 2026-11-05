"""切块。"""
from __future__ import annotations

from app.document.chunker import chunk_text


def test_chunk_keeps_offsets_and_order():
    text = "第一段内容。\n\n第二段内容。\n\n第三段内容。"
    result = chunk_text(text, max_tokens=60, overlap_tokens=0, min_tokens=1)
    assert result.chunks
    previous_end = -1
    for chunk in result.chunks:
        assert chunk.char_start >= 0
        assert chunk.char_end <= len(text)
        assert chunk.char_end > chunk.char_start
        assert chunk.char_start >= previous_end - 1
        previous_end = chunk.char_end


def test_markdown_heading_becomes_breadcrumb():
    text = "# 计划\n\n## 学习\n\n每天读书。\n\n## 健身\n\n每周跑步三次。"
    result = chunk_text(text, max_tokens=200, overlap_tokens=0, min_tokens=1)
    headings = [chunk.heading for chunk in result.chunks]
    assert any(heading and "计划" in heading and "学习" in heading for heading in headings)
    assert any(heading and "健身" in heading for heading in headings)


def test_long_paragraph_is_split():
    text = "很长的段落。" * 400
    result = chunk_text(text, max_tokens=80, overlap_tokens=10, min_tokens=1)
    assert len(result.chunks) > 1
    for chunk in result.chunks:
        assert chunk.token_count <= 80


def test_max_chunks_truncation():
    text = "\n\n".join(f"第{i}段内容。" * 20 for i in range(50))
    result = chunk_text(text, max_tokens=50, overlap_tokens=0, min_tokens=1, max_chunks=5)
    assert len(result.chunks) == 5
    assert result.truncated is True
    assert [chunk.index for chunk in result.chunks] == [0, 1, 2, 3, 4]
