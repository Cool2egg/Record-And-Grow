package com.recordandgrow.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 业务对话请求。
 *
 * <p>{@code conversationId} 可选：为空时每轮新建会话（单轮行为与模块四一致）；
 * 传入则由模块十加载历史与摘要。{@code question} 的长度上限由
 * {@code recordandgrow.chat.max-question-chars} 在 Service 里校验；这里只挡空值。
 * {@code maxTokens} 的上限在 Service 里按配置夹紧。
 */
public record ChatAskRequest(
        @NotBlank String question,
        @Min(64) @Max(4096) Integer maxTokens,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @Positive Long conversationId) {

    /** 模块四的 3 参构造：不带会话，保持旧调用与测试零改动。 */
    public ChatAskRequest(String question, Integer maxTokens, Double temperature) {
        this(question, maxTokens, temperature, null);
    }
}
