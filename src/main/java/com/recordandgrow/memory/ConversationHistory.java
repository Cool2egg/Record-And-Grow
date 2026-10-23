package com.recordandgrow.memory;

import com.recordandgrow.ai.ChatMessage;

import java.util.List;

/**
 * 一次会话的历史：滚动摘要 + 最近若干条原文。
 *
 * <p>摘要覆盖的原文已被压缩，不再重复放进来。
 */
public record ConversationHistory(String summary, List<ChatMessage> messages) {

    public ConversationHistory {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public boolean isEmpty() {
        return (summary == null || summary.isBlank()) && messages.isEmpty();
    }
}
