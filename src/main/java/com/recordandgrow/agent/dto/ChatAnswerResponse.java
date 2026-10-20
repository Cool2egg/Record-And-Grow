package com.recordandgrow.agent.dto;

import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.sse.SseEvents;

import java.util.List;

/**
 * 非流式对话响应。sources / retrieval 与 SSE 的 {@code event:sources} 形状一致。
 */
public record ChatAnswerResponse(
        String requestId,
        String answer,
        List<SseEvents.SourceItem> sources,
        SseEvents.RetrievalMeta retrieval,
        String finishReason,
        ChatUsage usage) {

    public ChatAnswerResponse {
        sources = List.copyOf(sources);
    }
}
