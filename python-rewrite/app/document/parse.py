"""文档解析：PDF / Word / Markdown / 纯文本 → 归一化全文。"""
from __future__ import annotations

import io
import re

from app.core.errors import BusinessException, ErrorCodes

_MIME_BY_EXT = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "md": "text/markdown",
    "markdown": "text/markdown",
    "txt": "text/plain",
}


def extension_of(file_name: str) -> str:
    if "." not in file_name:
        return ""
    return file_name.rsplit(".", 1)[1].lower()


def mime_type_of(file_name: str) -> str:
    return _MIME_BY_EXT.get(extension_of(file_name), "application/octet-stream")


def display_title(file_name: str) -> str:
    stem = file_name.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
    if "." in stem:
        stem = stem.rsplit(".", 1)[0]
    return stem.strip() or file_name


def parse_document(file_name: str, data: bytes) -> tuple[str, str]:
    """返回 ``(title, normalized_text)``。解析失败抛 ``DOCUMENT_PARSE_FAILED``。"""
    ext = extension_of(file_name)
    try:
        if ext == "pdf":
            raw = _parse_pdf(data)
        elif ext == "docx":
            raw = _parse_docx(data)
        elif ext in ("md", "markdown", "txt"):
            raw = data.decode("utf-8", errors="replace")
        else:
            raise BusinessException(ErrorCodes.UNSUPPORTED_MEDIA_TYPE, "不支持的文件类型")
    except BusinessException:
        raise
    except Exception as exc:  # noqa: BLE001 - 解析库异常种类多，统一翻译
        raise BusinessException(ErrorCodes.DOCUMENT_PARSE_FAILED, f"文档解析失败：{exc}") from exc

    text = normalize_text(raw)
    if not text.strip():
        raise BusinessException(ErrorCodes.DOCUMENT_PARSE_FAILED, "文档没有可解析的文本")
    return display_title(file_name), text


def _parse_pdf(data: bytes) -> str:
    import fitz  # pymupdf

    pages: list[str] = []
    with fitz.open(stream=data, filetype="pdf") as doc:
        for page in doc:
            pages.append(page.get_text("text"))
    return "\n\n".join(pages)


def _parse_docx(data: bytes) -> str:
    from docx import Document

    document = Document(io.BytesIO(data))
    blocks: list[str] = []
    for paragraph in document.paragraphs:
        text = paragraph.text.strip()
        if text:
            blocks.append(text)
    for table in document.tables:
        for row in table.rows:
            cells = [cell.text.strip() for cell in row.cells]
            if any(cells):
                blocks.append(" | ".join(cells))
    return "\n\n".join(blocks)


_ZERO_WIDTH = re.compile(r"[​-‏‪-‮﻿]")


def normalize_text(text: str) -> str:
    """统一换行、去掉零宽字符、压掉连续空行。"""
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = _ZERO_WIDTH.sub("", normalized)
    normalized = re.sub(r"[ \t　]+\n", "\n", normalized)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    return normalized.strip()
