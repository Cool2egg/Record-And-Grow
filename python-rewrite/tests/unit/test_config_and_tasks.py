"""配置环境变量映射与后台任务停机。"""
from __future__ import annotations

import asyncio

from app.core.config import (
    AgentSettings,
    AiChatSettings,
    McpSettings,
    MemorySettings,
    RerankSettings,
    SkillsSettings,
)
from app.core.tasks import shutdown, spawn


def test_flat_env_switches_are_read(monkeypatch):
    monkeypatch.setenv("MCP_ENABLED", "false")
    monkeypatch.setenv("MCP_AUTH_TOKEN", "secret")
    monkeypatch.setenv("AGENT_ENABLED", "false")
    monkeypatch.setenv("MEMORY_EXTRACTION_ENABLED", "false")
    monkeypatch.setenv("RERANK_ENABLED", "true")
    monkeypatch.setenv("SKILLS_DIR", "./my-skills")

    assert McpSettings().enabled is False
    assert McpSettings().auth_token == "secret"
    assert AgentSettings().enabled is False
    assert MemorySettings().extraction_enabled is False
    assert RerankSettings().enabled is True
    assert SkillsSettings().dir == "./my-skills"


def test_nested_settings_read_dotenv(tmp_path, monkeypatch):
    """嵌套配置类也必须读 .env：否则本地只配文件时密钥会被忽略。"""
    (tmp_path / ".env").write_text("DEEPSEEK_API_KEY=from-dotenv\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    assert AiChatSettings().api_key == "from-dotenv"


async def test_spawn_completes():
    done = asyncio.Event()

    async def quick() -> None:
        done.set()

    task = spawn(quick(), name="quick")
    await task
    assert done.is_set()
    assert task.done()


async def test_shutdown_cancels_slow_tasks():
    async def slow() -> None:
        await asyncio.sleep(30)

    task = spawn(slow(), name="slow")
    await asyncio.sleep(0)
    await shutdown(timeout_s=0.05)
    assert task.cancelled()
