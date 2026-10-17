package com.recordandgrow.ai.dto;

import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.FinishReason;

/**
 * AI 自检接口的非流式响应。
 */
public record AiChatResponse(String content, String finishReason, Usage usage) {

    public static AiChatResponse from(ChatResponse response) {
        FinishReason reason = response.finishReason();
        ChatUsage usage = response.usage() == null ? ChatUsage.empty() : response.usage();
        return new AiChatResponse(
                response.content(),
                reason == null ? null : reason.name(),
                new Usage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens()));
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
