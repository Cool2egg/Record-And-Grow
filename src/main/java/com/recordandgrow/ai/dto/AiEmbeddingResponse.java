package com.recordandgrow.ai.dto;

/**
 * Embedding 自检响应：只返回元信息，不回传 1024 个浮点数。
 */
public record AiEmbeddingResponse(int count, int dimensions) {
}
