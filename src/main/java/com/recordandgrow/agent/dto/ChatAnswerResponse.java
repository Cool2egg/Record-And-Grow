package com.recordandgrow.agent.dto;

import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.sse.SseEvents;

import java.util.List;

/**
 * 非流式对话响应。sources / retrieval 与 SSE 的 {@code event:sources} 形状一致。
 *
 * @param conversationId 本次会话 id；客户端下一轮带上它即可续接上下文
 */
public record ChatAnswerResponse(
        String requestId,
        String answer,
        List<SseEvents.SourceItem> sources,
        SseEvents.RetrievalMeta retrieval,
        String finishReason,
        ChatUsage usage,
        Long conversationId) {

    public ChatAnswerResponse {
        sources = List.copyOf(sources);
    }

    /** 模块四的 6 参构造：无会话。 */
    public ChatAnswerResponse(String requestId, String answer, List<SseEvents.SourceItem> sources,
                              SseEvents.RetrievalMeta retrieval, String finishReason, ChatUsage usage) {
        this(requestId, answer, sources, retrieval, finishReason, usage, null);
    }
}
