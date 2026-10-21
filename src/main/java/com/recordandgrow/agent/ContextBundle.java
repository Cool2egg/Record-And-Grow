package com.recordandgrow.agent;

import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.sse.SseEvents;

import java.util.List;

/**
 * 一次提问的完整上下文：Prompt 消息、来源、预算统计。
 *
 * <p>Prompt 与 sources 必须来自同一次组装，编号才能一一对应。
 *
 * <p>{@code retrievedCount / includedCount / droppedCount / budgetTruncated} 仍是**时间线口径**，
 * 与模块四一致；文档计数从 {@code sources().retrieval()} 取。
 * {@code documentsBudgetTruncated} = 文档召回数大于实际纳入数，只供日志与单测，不进 {@code sources}。
 */
public record ContextBundle(
        List<ChatMessage> messages,
        SseEvents.Sources sources,
        int promptChars,
        int retrievedCount,
        int includedCount,
        int droppedCount,
        boolean budgetTruncated,
        boolean documentsBudgetTruncated) {

    public ContextBundle {
        messages = List.copyOf(messages);
    }
}
