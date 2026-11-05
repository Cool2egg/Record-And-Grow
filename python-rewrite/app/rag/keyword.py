"""关键词抽取：引号短语 + 连续 ASCII 词，用于给时间窗检索加过滤。

中文自由文本不做分词——没有分词器时抽错的概率高于收益。
"""
from __future__ import annotations

import re

_QUOTED = re.compile(r'["“]([^"”]{2,40})["”]')
_ASCII_WORD = re.compile(r"[A-Za-z][A-Za-z0-9+#._-]{1,40}")
_MAX_KEYWORDS = 5

_STOPWORDS = {
    "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "with", "about",
    "me", "my", "i", "you", "your", "we", "our",
    "what", "which", "who", "how", "when", "where", "why",
    "did", "do", "does", "is", "are", "was", "were", "be", "been",
    "have", "has", "had", "can", "could", "should", "would",
    "this", "that", "these", "those",
    "last", "recent", "recently", "today", "yesterday", "tomorrow",
    "day", "days", "week", "weeks", "month", "months", "year", "years",
    "http", "https", "www", "com",
}


def _contains_token(phrase: str, token: str) -> bool:
    return token in re.split(r"[^\w+#._-]+", phrase)


def extract_keywords(question: str | None) -> list[str]:
    if not question or not question.strip():
        return []
    seen: set[str] = set()
    result: list[str] = []

    def add(candidate: str) -> None:
        trimmed = candidate.strip()
        if len(trimmed) < 2:
            return
        lower = trimmed.lower()
        if lower in _STOPWORDS:
            return
        if any(_contains_token(existing, lower) for existing in seen):
            return
        if lower in seen:
            return
        seen.add(lower)
        result.append(trimmed)

    for m in _QUOTED.finditer(question):
        if len(result) >= _MAX_KEYWORDS:
            break
        add(m.group(1))
    for m in _ASCII_WORD.finditer(question):
        if len(result) >= _MAX_KEYWORDS:
            break
        add(m.group())
    return result


_PUNCT_ONLY = re.compile(r"[\W_]+")
_MAX_SEARCH_TERMS = 8


def segment_for_search(question: str | None) -> list[str]:
    """关键词通道用的分词：jieba 搜索模式；未安装时退回标点切分。

    只用于文档侧召回，不影响时间线过滤（后者只抽高置信关键词）。
    """
    if not question or not question.strip():
        return []
    text = question.strip()
    try:
        import jieba

        tokens = [token.strip() for token in jieba.cut_for_search(text)]
    except Exception:  # noqa: BLE001 - 无 jieba 时退回简单切分
        tokens = [token.strip() for token in re.split(r"[\s　，。！？、,.!?；;：:（）()\[\]【】/]+", text)]

    seen: set[str] = set()
    terms: list[str] = []
    for token in tokens:
        if len(token) < 2 or _PUNCT_ONLY.fullmatch(token):
            continue
        if token.isascii() and token.lower() in _STOPWORDS:
            continue
        lower = token.lower()
        if lower in seen:
            continue
        seen.add(lower)
        terms.append(token)
        if len(terms) >= _MAX_SEARCH_TERMS:
            break
    return terms
