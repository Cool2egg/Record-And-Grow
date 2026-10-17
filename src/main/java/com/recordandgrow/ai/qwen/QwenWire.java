package com.recordandgrow.ai.qwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Qwen OpenAI 兼容 Embedding 的线格式。只在本包内使用。
 */
public final class QwenWire {

    private QwenWire() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingResponse(List<Item> data, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(float[] embedding, int index) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                        @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
