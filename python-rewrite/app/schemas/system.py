"""系统状态与技能。"""
from __future__ import annotations

from datetime import datetime

from app.schemas.base import BaseSchema


class HealthStatus(BaseSchema):
    status: str
    database: str
    vector_extension: bool


class Skill(BaseSchema):
    name: str
    description: str
    triggers: list[str] = []
    body: str
    source: str


class SystemStatus(BaseSchema):
    name: str
    version: str
    started_at: datetime
    java_version: str
    ai_configured: bool
    embedding_configured: bool
    agent_enabled: bool
    graph_enabled: bool
    graph_store: str
    mcp_enabled: bool
    skills: list[Skill] = []
