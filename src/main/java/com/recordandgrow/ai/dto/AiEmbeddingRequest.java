package com.recordandgrow.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 自检接口的 Embedding 请求。
 */
public record AiEmbeddingRequest(
        @NotEmpty @Size(max = 1000) List<@NotBlank String> texts) {
}
