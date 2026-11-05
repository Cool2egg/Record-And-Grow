"""关键词抽取与文本归一化。"""
from __future__ import annotations

from app.document.parse import normalize_text
from app.rag.keyword import extract_keywords


def test_extracts_quoted_phrase():
    assert extract_keywords("帮我查一下“Spring Boot 笔记”里的内容") == ["Spring Boot 笔记"]


def test_extracts_ascii_words_and_skips_stopwords():
    keywords = extract_keywords("What did I do about Postgres and Redis last week")
    assert "Postgres" in keywords
    assert "Redis" in keywords
    assert "What" not in keywords
    assert "week" not in keywords


def test_no_keywords_for_pure_chinese_sentence():
    assert extract_keywords("上个月我都在做什么") == []


def test_max_five_keywords():
    text = " ".join(f"term{i}" for i in range(10))
    assert len(extract_keywords(text)) <= 5


def test_normalize_text_collapses_blank_lines_and_bom():
    raw = "﻿第一段\r\n\r\n\r\n\r\n第二段  \r\n"
    assert normalize_text(raw) == "第一段\n\n第二段"
