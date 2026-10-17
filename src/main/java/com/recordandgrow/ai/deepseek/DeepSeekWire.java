package com.recordandgrow.ai.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek OpenAI 兼容接口的线格式。只在本包内使用。
 */
public final class DeepSeekWire {

    private DeepSeekWire() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chunk(List<ChunkChoice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkChoice(Delta delta, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String content, String role,
                        @JsonProperty("reasoning_content") String reasoningContent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                        @JsonProperty("completion_tokens") Integer completionTokens,
                        @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
