"""端到端联调脚本：打真实的 HTTP 接口，断言契约与业务流程。

前置：
1. PostgreSQL 已起并跑过 `alembic upgrade head`
2. 应用以桩模型服务启动：
   DEEPSEEK_BASE_URL=http://127.0.0.1:9099 DEEPSEEK_API_KEY=test \
   DASHSCOPE_BASE_URL=http://127.0.0.1:9099 DASHSCOPE_API_KEY=test \
   uv run uvicorn app.main:app --port 8080
3. 桩服务：uv run uvicorn stub_model_server:app --app-dir scripts --port 9099

运行：uv run python scripts/e2e_smoke.py
"""
from __future__ import annotations

import json
import os
import sys
import time
import uuid

import httpx

BASE = "http://127.0.0.1:8080"
LIVE = os.environ.get("E2E_LIVE") == "1"  # 真实模型：跳过桩特有断言
passed: list[str] = []


def check(name: str, condition: bool, detail: object = "") -> None:
    if not condition:
        print(f"FAIL {name}: {detail}")
        sys.exit(1)
    passed.append(name)
    print(f"PASS {name}")


def wait_for(fn, timeout: float = 20.0, interval: float = 0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = fn()
        if value is not None:
            return value
        time.sleep(interval)
    return None


def wait_document(client: httpx.Client, document_id: int) -> dict:
    def probe():
        data = client.get(f"/api/documents/{document_id}").json()["data"]
        return data if data["status"] in ("READY", "FAILED") else None

    document = wait_for(probe)
    check("document finished", document is not None and document["status"] == "READY", document)
    return document


def wait_graph(client: httpx.Client) -> dict:
    def probe():
        data = client.get("/api/graph/stats").json()["data"]
        return data if data["nodes"] >= 2 else None

    stats = wait_for(probe, timeout=20)
    check("graph extracted", stats is not None, stats)
    return stats


def wait_pending(client: httpx.Client, timeout: float = 20.0) -> dict | None:
    def probe():
        items = client.get("/api/memories/pending", params={"limit": 10}).json()["data"]
        return items[0] if items else None

    return wait_for(probe, timeout=timeout)


def stream_chat(client: httpx.Client, question: str) -> list[tuple[str, str]]:
    events: list[tuple[str, str]] = []
    with client.stream("POST", "/api/chat/stream", json={"question": question}) as response:
        if response.status_code != 200:
            check("sse http 200", False, response.read().decode("utf-8", "replace"))
        event: str | None = None
        data_lines: list[str] = []
        for line in response.iter_lines():
            line = line.rstrip("\r")
            if not line:
                if event and data_lines:
                    events.append((event, "\n".join(data_lines)))
                event, data_lines = None, []
                continue
            if line.startswith(":"):
                continue
            if line.startswith("event:"):
                event = line[len("event:"):].strip()
            elif line.startswith("data:"):
                data_lines.append(line[len("data:"):].lstrip())
    return events


def main() -> None:
    with httpx.Client(base_url=BASE, timeout=180.0) as client:
        health = client.get("/api/health")
        check("health 200", health.status_code == 200 and health.json()["code"] == 0)
        check(
            "health up + vector",
            health.json()["data"]["status"] == "UP"
            and health.json()["data"]["vectorExtension"] is True,
        )

        # 时间线：创建 / PUT / 列表 / 时区
        created = client.post(
            "/api/timeline",
            json={"content": "跑步 30 分钟", "startTime": "2026-11-03T09:00:00+08:00", "endTime": None},
        ).json()["data"]
        check("timeline utc", created["startTime"] == "2026-11-03T01:00:00+00:00", created)
        entry_id = created["id"]
        updated = client.put(
            f"/api/timeline/{entry_id}",
            json={
                "content": "跑步 30 分钟",
                "startTime": "2026-11-03T09:00:00+08:00",
                "endTime": "2026-11-03T10:00:00+08:00",
            },
        ).json()["data"]
        check("timeline put end", updated["endTime"] == "2026-11-03T02:00:00+00:00", updated)
        listed = client.get(
            "/api/timeline",
            params={
                "start": "2026-11-01T00:00:00+08:00",
                "end": "2026-11-05T00:00:00+08:00",
                "page": 1,
                "size": 20,
            },
        ).json()["data"]
        check("timeline list", listed["total"] >= 1)

        # 文档：上传 → 解析 → 切块 → 向量 → READY
        marker = uuid.uuid4().hex
        content = f"这是一份测试文档。独特标记 {marker}。每天读书三十页。"
        uploaded = client.post(
            "/api/documents",
            files={"file": ("测试文档.txt", content.encode("utf-8"), "text/plain")},
        )
        check(
            "upload 202",
            uploaded.status_code == 202 and uploaded.json()["data"]["deduplicated"] is False,
        )
        document_id = uploaded.json()["data"]["document"]["id"]
        document = wait_document(client, document_id)
        check("document chunks", (document["chunkCount"] or 0) >= 1, document)
        chunks = client.get(
            f"/api/documents/{document_id}/chunks", params={"page": 1, "size": 20}
        ).json()["data"]["items"]
        check("chunks listed", len(chunks) >= 1)
        located = client.get(
            f"/api/documents/{document_id}/chunks/{chunks[0]['id']}", params={"size": 20}
        ).json()["data"]
        check("locate chunk", located["chunk"]["id"] == chunks[0]["id"] and located["page"] == 1)

        # 图谱：桩模式断言抽取结果；真实模式只检查接口可用（抽取质量另评）
        if LIVE:
            stats = client.get("/api/graph/stats").json()["data"]
            check("graph stats", "nodes" in stats and "edges" in stats, stats)
        else:
            wait_graph(client)
            graph_hits = client.get("/api/graph/search", params={"q": "测试实体A", "limit": 20}).json()["data"]
            check("graph search", any(hit["source"] == "测试实体A" for hit in graph_hits["hits"]), graph_hits)

        # 聊天：SSE 顺序、sources 不变式、落库
        events = stream_chat(client, "我最近做了什么")
        names = [name for name, _ in events]
        check("sse start first", names and names[0] == "start", names)
        check("sse steps before sources", "step" in names and "sources" in names and names.index("step") < names.index("sources"), names)
        check("sse sources before delta", "sources" in names and names.index("sources") < names.index("delta"))
        check("sse done last", names and names[-1] == "done", names)
        start = json.loads(events[0][1])
        check("start has conversationId", bool(start.get("conversationId")), start)
        sources = json.loads(events[names.index("sources")][1])
        check("sources invariant", len(sources["items"]) == sources["retrieval"]["totalIncluded"])
        check("sources timeline", any(item["type"] == "timeline" for item in sources["items"]))
        if not LIVE:
            check(
                "sources document",
                any(item["type"] == "document" for item in sources["items"]),
                sources["items"],
            )
        answer = "".join(
            json.loads(data)["content"] for name, data in events if name == "delta"
        )
        check("delta content", bool(answer.strip()) if LIVE else "测试" in answer, answer)
        check("done usage", "usage" in json.loads(events[-1][1]))

        conversation_id = start["conversationId"]
        messages = client.get(f"/api/conversations/{conversation_id}/messages").json()["data"]["items"]
        check(
            "messages persisted",
            len(messages) == 2
            and messages[1]["role"] == "ASSISTANT"
            and bool(messages[1]["sources"]),
            messages,
        )

        # 非流式对话
        plain = client.post("/api/chat", json={"question": "我最近做了什么"})
        check("chat non-stream", plain.json()["code"] == 0 and plain.json()["data"]["content"])

        # 错误事件：桩能强制上游失败；真实模型无法安全触发，跳过
        if not LIVE:
            failed_events = stream_chat(client, "触发错误")
            failed_names = [name for name, _ in failed_events]
            check(
                "sse error terminal",
                failed_names and failed_names[-1] == "error" and "done" not in failed_names,
                failed_names,
            )

        # 记忆：桩会固定产出候选；真实模型不保证，有就验证三态
        pending = wait_pending(client, timeout=20)
        if pending is not None:
            checked = client.post(
                f"/api/memories/{pending['id']}/confirm", json={"type": "FACT"}
            ).json()["data"]
            check("memory confirm", checked["type"] == "FACT")
            facts = client.get("/api/memories", params={"type": "FACT"}).json()["data"]["items"]
            check("memory list", any(item["id"] == pending["id"] for item in facts))
        elif not LIVE:
            check("memory pending", False, None)
        else:
            print("SKIP memory pending（真实模型本轮没有产出候选）")

        # 系统与 MCP
        system = client.get("/api/system").json()["data"]
        check("system ai configured", system["aiConfigured"] is True)
        tools = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).json()
        check("mcp tools", len(tools["result"]["tools"]) >= 6)
        called = client.post(
            "/mcp",
            json={
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {"name": "search_timeline", "arguments": {"query": "跑步"}},
            },
        ).json()
        check("mcp tool call", "content" in called["result"], called)

        # 清理
        client.delete(f"/api/documents/{document_id}")
        client.delete(f"/api/timeline/{entry_id}")
        client.delete(f"/api/conversations/{conversation_id}")

    print(f"\nALL PASS ({len(passed)} checks)")


if __name__ == "__main__":
    main()
