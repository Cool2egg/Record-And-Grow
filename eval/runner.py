"""录制：把评估集逐条打给被测应用，落成 runs/*.jsonl。

只录"系统输出了什么"，不做任何判定——判定属于 metrics，可以反复重跑而不花钱。
用标准库 urllib，避免为了几行 HTTP 再装 requests。
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .schema import Observation, Sample


def _post(url: str, payload: dict[str, Any], timeout: float) -> tuple[int, dict[str, Any]]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"message": raw[:200]}


def record_one(sample: Sample, base_url: str, timeout: float) -> Observation:
    started = time.perf_counter()
    status = 0
    body: dict[str, Any] = {}
    error: str | None = None
    try:
        status, body = _post(f"{base_url.rstrip('/')}/api/chat", {"question": sample.question}, timeout)
    except Exception as exc:  # 连接失败、超时、DNS 都算这一次失败，不中断整批
        error = type(exc).__name__
    elapsed_ms = int((time.perf_counter() - started) * 1000)

    data = body.get("data") or {}
    if error is None and body.get("code", 0) not in (0, None):
        error = f"biz_{body.get('code')}"
    if error is None and not data:
        error = "empty_data"

    return Observation(
        id=sample.id,
        question=sample.question,
        answer=data.get("answer") or "",
        sources=data.get("sources") or [],
        http_status=status,
        total_ms=elapsed_ms,
        route=(data.get("retrieval") or {}).get("route"),
        error=error,
        retrieval=data.get("retrieval") or None,
    )


def record_all(
    samples: list[Sample],
    base_url: str,
    out_path: str | Path,
    timeout: float = 120.0,
    limit: int | None = None,
) -> Path:
    selected = samples[:limit] if limit else samples
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as sink:
        for index, sample in enumerate(selected, start=1):
            observation = record_one(sample, base_url, timeout)
            sink.write(json.dumps(observation.to_dict(), ensure_ascii=False) + "\n")
            sink.flush()
            flag = "OK " if observation.error is None else f"ERR({observation.error})"
            print(f"[{index}/{len(selected)}] {sample.id:<8} {flag} {observation.total_ms}ms", flush=True)
    return out
