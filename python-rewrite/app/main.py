"""应用入口：装配路由、异常处理、后台对账任务。"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.errors import install_exception_handlers
from app.api.middleware import RequestContextMiddleware
from app.api.routes_chat import router as chat_router
from app.api.routes_conversation import router as conversation_router
from app.api.routes_document import router as document_router
from app.api.routes_graph import router as graph_router
from app.api.routes_mcp import router as mcp_router
from app.api.routes_memory import router as memory_router
from app.api.routes_system import router as system_router
from app.api.routes_timeline import router as timeline_router
from app.core.config import settings
from app.core.logging import configure_logging, get_logger
from app.core.tasks import shutdown as shutdown_tasks
from app.services import document as document_service
from app.services.skills import get_skill_registry

configure_logging()
log = get_logger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    get_skill_registry()
    log.info("app_started", name=settings.name, version=settings.version)
    reconcile_task: asyncio.Task | None = None
    if settings.document.reconcile.enabled:
        reconcile_task = asyncio.create_task(document_service.reconcile_loop())
    try:
        yield
    finally:
        if reconcile_task is not None:
            reconcile_task.cancel()
            try:
                await reconcile_task
            except asyncio.CancelledError:
                pass
        # 给在途的入库/抽取任务一个收尾窗口，超时再取消
        await shutdown_tasks(timeout_s=10.0)
        log.info("app_stopped")


app = FastAPI(title=settings.name, version=settings.version, lifespan=lifespan)
install_exception_handlers(app)
app.add_middleware(RequestContextMiddleware)

for router in (
    chat_router,
    conversation_router,
    timeline_router,
    memory_router,
    document_router,
    graph_router,
    system_router,
    mcp_router,
):
    app.include_router(router)


@app.get("/")
async def root() -> dict[str, str]:
    return {"name": settings.name, "version": settings.version}
