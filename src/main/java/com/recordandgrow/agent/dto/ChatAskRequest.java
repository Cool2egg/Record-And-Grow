package com.recordandgrow.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 业务对话请求。本模块单轮，不接受 history。
 *
 * <p>{@code question} 的长度上限由 {@code recordandgrow.chat.max-question-chars}
 * 在 Service 里校验；这里只挡空值。{@code maxTokens} 的上限在 Service 里按配置夹紧。
 */
public record ChatAskRequest(
        @NotBlank String question,
        @Min(64) @Max(4096) Integer maxTokens,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature) {
}
