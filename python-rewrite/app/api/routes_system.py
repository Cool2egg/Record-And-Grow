"""健康检查、系统状态、技能接口。"""
from __future__ import annotations

import platform
from datetime import UTC, datetime

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.response import ApiResponse
from app.db.session import get_session
from app.rag.models import get_embedding_client, get_model
from app.schemas.system import Skill as SkillSchema
from app.schemas.system import SystemStatus
from app.services import health as health_service
from app.services.skills import Skill, get_skill_registry

router = APIRouter(prefix="/api", tags=["system"])

STARTED_AT = datetime.now(UTC)


@router.get("/health")
async def health(session: AsyncSession = Depends(get_session)):
    return ApiResponse.success(await health_service.check(session))


@router.get("/system")
async def system():
    registry = get_skill_registry()
    status = SystemStatus(
        name=settings.name,
        version=settings.version,
        started_at=STARTED_AT,
        java_version=platform.python_version(),
        ai_configured=get_model("chat").is_configured(),
        embedding_configured=get_embedding_client().is_configured(),
        agent_enabled=settings.agent.enabled,
        graph_enabled=settings.graph.enabled,
        graph_store="postgres",
        mcp_enabled=settings.mcp.enabled,
        skills=[_to_schema(skill) for skill in registry.all()],
    )
    return ApiResponse.success(status)


@router.get("/skills")
async def skills():
    return ApiResponse.success([_to_schema(skill) for skill in get_skill_registry().all()])


@router.post("/skills/reload")
async def reload_skills():
    loaded = get_skill_registry().reload()
    return ApiResponse.success({"loaded": loaded})


def _to_schema(skill: Skill) -> SkillSchema:
    return SkillSchema(
        name=skill.name,
        description=skill.description,
        triggers=skill.triggers,
        body=skill.body,
        source=skill.source,
    )
