"""关键词通道分词。"""
from __future__ import annotations

from app.rag.keyword import segment_for_search


def test_segments_chinese_question():
    terms = segment_for_search("我想了解学习计划的执行方法")
    assert terms
    assert any("学习" in term or "计划" in term for term in terms)
    assert all(len(term) >= 2 for term in terms)


def test_ascii_terms_and_stopwords():
    terms = segment_for_search("What about Postgres and Redis")
    lowered = {term.lower() for term in terms}
    assert "postgres" in lowered
    assert "redis" in lowered
    assert "what" not in lowered
    assert "and" not in lowered


def test_punctuation_only_returns_empty():
    assert segment_for_search("？？！！") == []
    assert segment_for_search("   ") == []


def test_caps_at_eight_terms():
    question = "学习计划 复习计划 备考计划 健身计划 旅行计划 读书计划 写作计划 编程计划 英语计划"
    assert len(segment_for_search(question)) <= 8
