"""本地文件存储：按 sha256 分桶落盘。"""
from __future__ import annotations

import hashlib
from pathlib import Path

from app.core.config import settings


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def storage_path_for(sha256: str, file_name: str) -> Path:
    ext = file_name.rsplit(".", 1)[-1].lower() if "." in file_name else "bin"
    root = Path(settings.document.storage_dir)
    return root / sha256[:2] / f"{sha256}.{ext}"


def save_file(sha256: str, file_name: str, data: bytes) -> str:
    path = storage_path_for(sha256, file_name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return str(path)


def save_file_from_path(sha256: str, file_name: str, tmp_path: Path) -> str:
    """把已落盘的临时文件移动到最终位置（同一文件系统内 rename）。"""
    target = storage_path_for(sha256, file_name)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp_path.replace(target)
    return str(target)


def read_file(path: str) -> bytes:
    return Path(path).read_bytes()


def delete_file(path: str) -> None:
    try:
        Path(path).unlink(missing_ok=True)
    except OSError:
        pass
