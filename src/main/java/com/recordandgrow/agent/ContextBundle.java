package com.recordandgrow.agent;

import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.sse.SseEvents;

import java.util.List;

/**
 * 一次提问的完整上下文：Prompt 消息、来源、预算统计。
 *
 * <p>Prompt 与 sources 必须来自同一次组装，编号才能一一对应。
 */
public record ContextBundle(
        List<ChatMessage> messages,
        SseEvents.Sources sources,
        int promptChars,
        int retrievedCount,
        int includedCount,
        int droppedCount,
        boolean budgetTruncated) {

    public ContextBundle {
        messages = List.copyOf(messages);
    }
}
