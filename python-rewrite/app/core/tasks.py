"""后台任务登记：应用停机时先等一会儿，再取消剩余任务。"""
from __future__ import annotations

import asyncio
from collections.abc import Awaitable
from typing import Any

from app.core.logging import get_logger

log = get_logger(__name__)

_tasks: set[asyncio.Task[Any]] = set()


def spawn(coro: Awaitable[Any], name: str | None = None) -> asyncio.Task[Any]:
    """登记一个后台任务。没有登记的任务在停机时会被直接掐断。"""
    task = asyncio.get_running_loop().create_task(coro, name=name)
    _tasks.add(task)
    task.add_done_callback(_discard)
    return task


def _discard(task: asyncio.Task[Any]) -> None:
    _tasks.discard(task)
    if task.cancelled():
        return
    error = task.exception()
    if error is not None:
        log.warning("background_task_failed", task=task.get_name(), error=str(error))


def pending_count() -> int:
    return len(_tasks)


async def shutdown(timeout_s: float = 10.0) -> None:
    """给在途任务一个收尾窗口，超时后取消。"""
    pending = {task for task in _tasks if not task.done()}
    if not pending:
        return
    log.info("background_shutdown", pending=len(pending))
    done, still = await asyncio.wait(pending, timeout=timeout_s)
    for task in still:
        task.cancel()
    if still:
        await asyncio.gather(*still, return_exceptions=True)
    log.info("background_shutdown_done", completed=len(done), cancelled=len(still))
