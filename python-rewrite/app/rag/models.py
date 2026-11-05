"""模型层：OpenAI 兼容接口的 Chat / Embedding / Rerank 客户端。

业务代码只依赖这里的 ``get_model()`` 与返回结构，不关心底层是 DeepSeek、
DashScope 还是别的兼容服务。所有失败都映射成 ``AiException`` + 明确的错误类型。
"""
from __future__ import annotations

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.core.config import settings
from app.core.errors import AiErrorType, AiException


@dataclass
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0

    def model_dump(self) -> dict[str, int]:
        return {
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "total_tokens": self.total_tokens,
        }


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: str = "{}"


@dataclass
class Completion:
    content: str
    finish_reason: str | None
    usage: Usage
    tool_calls: list[ToolCall] = field(default_factory=list)


@dataclass
class StreamChunk:
    content: str = ""
    finish_reason: str | None = None
    usage: Usage | None = None


def _error_type_for_status(status: int) -> AiErrorType:
    if status in (401, 403):
        return AiErrorType.NOT_CONFIGURED
    if status == 429:
        return AiErrorType.RATE_LIMITED
    if status == 400:
        return AiErrorType.INVALID_REQUEST
    return AiErrorType.UPSTREAM_ERROR


class OpenAiChatClient:
    """OpenAI 兼容的对话客户端。"""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        fallback_model: str | None = None,
        fallback_enabled: bool = True,
        connect_timeout_s: float = 5.0,
        total_timeout_s: float = 180.0,
        max_attempts: int = 2,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key or ""
        self.model = model
        self.fallback_model = fallback_model
        self.fallback_enabled = fallback_enabled
        self.connect_timeout_s = connect_timeout_s
        self.total_timeout_s = total_timeout_s
        self.max_attempts = max(1, max_attempts)

    def is_configured(self) -> bool:
        return bool(self.api_key.strip())

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

    def _timeout(self) -> httpx.Timeout:
        return httpx.Timeout(self.total_timeout_s, connect=self.connect_timeout_s)

    def _models_to_try(self) -> list[str]:
        models = [self.model]
        if (
            self.fallback_enabled
            and self.fallback_model
            and self.fallback_model != self.model
        ):
            models.append(self.fallback_model)
        return models

    async def complete(
        self,
        messages: list[dict[str, Any]],
        max_tokens: int | None = None,
        temperature: float | None = None,
        tools: list[dict[str, Any]] | None = None,
        timeout_s: float | None = None,
    ) -> Completion:
        last_error: AiException | None = None
        for model in self._models_to_try():
            try:
                return await self._complete_once(model, messages, max_tokens, temperature, tools, timeout_s)
            except AiException as exc:
                last_error = exc
                if exc.error_type not in (
                    AiErrorType.UPSTREAM_ERROR,
                    AiErrorType.RATE_LIMITED,
                    AiErrorType.TIMEOUT,
                ):
                    raise
        raise last_error or AiException(AiErrorType.UPSTREAM_ERROR, "AI 服务暂时不可用")

    async def _complete_once(
        self,
        model: str,
        messages: list[dict[str, Any]],
        max_tokens: int | None,
        temperature: float | None,
        tools: list[dict[str, Any]] | None,
        timeout_s: float | None,
    ) -> Completion:
        payload: dict[str, Any] = {"model": model, "messages": messages, "stream": False}
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if temperature is not None:
            payload["temperature"] = temperature
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        timeout = httpx.Timeout(timeout_s, connect=self.connect_timeout_s) if timeout_s else self._timeout()
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(
                    f"{self.base_url}/chat/completions",
                    headers=self._headers(),
                    json=payload,
                )
        except httpx.TimeoutException as exc:
            raise AiException(AiErrorType.TIMEOUT, "AI 服务响应超时") from exc
        except httpx.HTTPError as exc:
            raise AiException(AiErrorType.UPSTREAM_ERROR, "AI 服务暂时不可用") from exc

        if response.status_code >= 400:
            raise AiException(
                _error_type_for_status(response.status_code),
                "AI 服务返回异常",
                status_code=response.status_code,
            )
        try:
            body = response.json()
            choice = body["choices"][0]
            message = choice.get("message") or {}
            content = message.get("content") or ""
            tool_calls = [
                ToolCall(
                    id=call.get("id") or f"call_{index}",
                    name=(call.get("function") or {}).get("name") or "",
                    arguments=(call.get("function") or {}).get("arguments") or "{}",
                )
                for index, call in enumerate(message.get("tool_calls") or [])
            ]
            usage = body.get("usage") or {}
            return Completion(
                content=content,
                finish_reason=choice.get("finish_reason"),
                usage=Usage(
                    prompt_tokens=int(usage.get("prompt_tokens") or 0),
                    completion_tokens=int(usage.get("completion_tokens") or 0),
                    total_tokens=int(usage.get("total_tokens") or 0),
                ),
                tool_calls=tool_calls,
            )
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise AiException(AiErrorType.BAD_RESPONSE, "AI 服务返回格式异常") from exc

    async def stream(
        self,
        messages: list[dict[str, Any]],
        max_tokens: int | None = None,
        temperature: float | None = None,
        timeout_s: float | None = None,
    ) -> AsyncIterator[StreamChunk]:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if temperature is not None:
            payload["temperature"] = temperature

        timeout = httpx.Timeout(timeout_s, connect=self.connect_timeout_s) if timeout_s else self._timeout()
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST",
                    f"{self.base_url}/chat/completions",
                    headers=self._headers(),
                    json=payload,
                ) as response:
                    if response.status_code >= 400:
                        raise AiException(
                            _error_type_for_status(response.status_code),
                            "AI 服务返回异常",
                            status_code=response.status_code,
                        )
                    async for line in response.aiter_lines():
                        if not line or not line.startswith("data:"):
                            continue
                        data = line[len("data:"):].strip()
                        if not data:
                            continue
                        if data == "[DONE]":
                            return
                        try:
                            body = json.loads(data)
                        except ValueError:
                            continue
                        choices = body.get("choices") or []
                        if not choices:
                            usage = body.get("usage")
                            if usage:
                                yield StreamChunk(
                                    usage=Usage(
                                        prompt_tokens=int(usage.get("prompt_tokens") or 0),
                                        completion_tokens=int(usage.get("completion_tokens") or 0),
                                        total_tokens=int(usage.get("total_tokens") or 0),
                                    )
                                )
                            continue
                        choice = choices[0]
                        delta = choice.get("delta") or {}
                        content = delta.get("content") or ""
                        finish_reason = choice.get("finish_reason")
                        usage = body.get("usage")
                        if content or finish_reason or usage:
                            yield StreamChunk(
                                content=content,
                                finish_reason=finish_reason,
                                usage=(
                                    Usage(
                                        prompt_tokens=int(usage.get("prompt_tokens") or 0),
                                        completion_tokens=int(usage.get("completion_tokens") or 0),
                                        total_tokens=int(usage.get("total_tokens") or 0),
                                    )
                                    if usage
                                    else None
                                ),
                            )
        except httpx.TimeoutException as exc:
            raise AiException(AiErrorType.TIMEOUT, "AI 服务响应超时") from exc
        except httpx.HTTPError as exc:
            raise AiException(AiErrorType.STREAM_INTERRUPTED, "AI 流式输出中断") from exc


class OpenAiEmbeddingClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        dimensions: int = 1024,
        batch_size: int = 10,
        connect_timeout_s: float = 5.0,
        timeout_s: float = 30.0,
        max_attempts: int = 2,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key or ""
        self.model = model
        self.dimensions = dimensions
        self.batch_size = max(1, batch_size)
        self.connect_timeout_s = connect_timeout_s
        self.timeout_s = timeout_s
        self.max_attempts = max(1, max_attempts)

    def is_configured(self) -> bool:
        return bool(self.api_key.strip())

    async def embed(
        self,
        texts: list[str],
        timeout_s: float | None = None,
        max_attempts: int | None = None,
    ) -> list[list[float]]:
        if not texts:
            return []
        attempts = max(1, max_attempts or self.max_attempts)
        timeout = httpx.Timeout(timeout_s or self.timeout_s, connect=self.connect_timeout_s)
        last_error: AiException | None = None
        for _ in range(attempts):
            try:
                vectors: list[list[float]] = []
                for start in range(0, len(texts), self.batch_size):
                    batch = texts[start:start + self.batch_size]
                    vectors.extend(await self._embed_batch(batch, timeout))
                return vectors
            except AiException as exc:
                last_error = exc
                if exc.error_type not in (AiErrorType.TIMEOUT, AiErrorType.UPSTREAM_ERROR, AiErrorType.RATE_LIMITED):
                    raise
        raise last_error or AiException(AiErrorType.UPSTREAM_ERROR, "向量服务暂时不可用")

    async def _embed_batch(self, batch: list[str], timeout: httpx.Timeout) -> list[list[float]]:
        payload = {
            "model": self.model,
            "input": batch,
            "dimensions": self.dimensions,
            "encoding_format": "float",
        }
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(
                    f"{self.base_url}/embeddings",
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
        except httpx.TimeoutException as exc:
            raise AiException(AiErrorType.TIMEOUT, "向量服务响应超时") from exc
        except httpx.HTTPError as exc:
            raise AiException(AiErrorType.UPSTREAM_ERROR, "向量服务暂时不可用") from exc

        if response.status_code >= 400:
            raise AiException(
                _error_type_for_status(response.status_code),
                "向量服务返回异常",
                status_code=response.status_code,
            )
        try:
            body = response.json()
            data = sorted(body["data"], key=lambda item: item.get("index", 0))
            return [item["embedding"] for item in data]
        except (KeyError, TypeError, ValueError) as exc:
            raise AiException(AiErrorType.BAD_RESPONSE, "向量服务返回格式异常") from exc


class DashScopeRerankClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        connect_timeout_s: float = 2.0,
        timeout_s: float = 1.5,
    ) -> None:
        self.base_url = base_url
        self.api_key = api_key or ""
        self.model = model
        self.connect_timeout_s = connect_timeout_s
        self.timeout_s = timeout_s

    def is_configured(self) -> bool:
        return bool(self.api_key.strip())

    async def rerank(self, query: str, documents: list[str], top_n: int) -> list[tuple[int, float]]:
        payload = {
            "model": self.model,
            "input": {"query": query, "documents": documents},
            "parameters": {"return_documents": False, "top_n": top_n},
        }
        timeout = httpx.Timeout(self.timeout_s, connect=self.connect_timeout_s)
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
        except httpx.TimeoutException as exc:
            raise AiException(AiErrorType.TIMEOUT, "精排服务响应超时") from exc
        except httpx.HTTPError as exc:
            raise AiException(AiErrorType.UPSTREAM_ERROR, "精排服务暂时不可用") from exc

        if response.status_code >= 400:
            raise AiException(
                _error_type_for_status(response.status_code),
                "精排服务返回异常",
                status_code=response.status_code,
            )
        try:
            results = response.json()["output"]["results"]
            return [
                (int(item.get("index", 0)), float(item.get("relevance_score", 0.0)))
                for item in results
            ]
        except (KeyError, TypeError, ValueError) as exc:
            raise AiException(AiErrorType.BAD_RESPONSE, "精排服务返回格式异常") from exc


_chat_client: OpenAiChatClient | None = None
_embedding_client: OpenAiEmbeddingClient | None = None
_rerank_client: DashScopeRerankClient | None = None


def get_model(role: str = "chat") -> OpenAiChatClient:
    """模型工厂：业务代码只依赖这个入口。目前所有角色共用同一配置。"""
    global _chat_client
    if _chat_client is None:
        cfg = settings.ai.chat
        _chat_client = OpenAiChatClient(
            base_url=cfg.base_url,
            api_key=cfg.api_key,
            model=cfg.model,
            fallback_model=cfg.fallback_model,
            fallback_enabled=cfg.fallback_enabled,
            connect_timeout_s=cfg.connect_timeout_s,
            total_timeout_s=cfg.total_timeout_s,
            max_attempts=cfg.max_attempts,
        )
    return _chat_client


def get_embedding_client() -> OpenAiEmbeddingClient:
    global _embedding_client
    if _embedding_client is None:
        cfg = settings.ai.embedding
        _embedding_client = OpenAiEmbeddingClient(
            base_url=cfg.base_url,
            api_key=cfg.api_key,
            model=cfg.model,
            dimensions=cfg.dimensions,
            batch_size=cfg.batch_size,
            connect_timeout_s=cfg.connect_timeout_s,
            timeout_s=cfg.timeout_s,
            max_attempts=cfg.max_attempts,
        )
    return _embedding_client


def get_rerank_client() -> DashScopeRerankClient:
    global _rerank_client
    if _rerank_client is None:
        cfg = settings.chat.rerank
        _rerank_client = DashScopeRerankClient(
            base_url=cfg.base_url,
            api_key=cfg.api_key,
            model=cfg.model,
            connect_timeout_s=cfg.connect_timeout_s,
            timeout_s=cfg.timeout_s,
        )
    return _rerank_client


def reset_clients() -> None:
    """测试用：清掉单例。"""
    global _chat_client, _embedding_client, _rerank_client
    _chat_client = None
    _embedding_client = None
    _rerank_client = None
