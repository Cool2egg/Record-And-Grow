"""接口冒烟：请求 id 透传、系统状态版本字段、MCP 鉴权。"""
from __future__ import annotations

from httpx import ASGITransport, AsyncClient

from app.core.config import settings
from app.main import app


def client() -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


async def test_request_id_header_is_set():
    async with client() as http:
        response = await http.get("/api/system")
        assert response.status_code == 200
        assert response.headers.get("x-request-id")


async def test_request_id_is_propagated():
    async with client() as http:
        response = await http.get("/api/system", headers={"X-Request-Id": "abc-123"})
        assert response.headers["x-request-id"] == "abc-123"


async def test_system_version_has_no_runtime_suffix():
    async with client() as http:
        payload = (await http.get("/api/system")).json()["data"]
        assert payload["javaVersion"].startswith("3.")
        assert "Python" not in payload["javaVersion"]


async def test_mcp_requires_token_when_configured(monkeypatch):
    monkeypatch.setattr(settings.mcp, "auth_token", "secret-token")
    body = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}

    async with client() as http:
        unauthorized = await http.post("/mcp", json=body)
        assert unauthorized.status_code == 401
        assert unauthorized.json()["error"]["code"] == -32001

        authorized = await http.post(
            "/mcp", json=body, headers={"Authorization": "Bearer secret-token"}
        )
        assert authorized.status_code == 200
        assert authorized.json()["result"]["serverInfo"]["name"]
