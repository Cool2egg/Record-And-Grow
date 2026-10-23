package com.recordandgrow.memory.dto;

import com.recordandgrow.memory.Conversation;

import java.time.OffsetDateTime;

/**
 * 会话响应。summary 是早期消息的滚动摘要，仅用于会话列表展示与调试。
 */
public record ConversationResponse(
        Long id,
        String title,
        String summary,
        int messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getSummary(),
                conversation.getMessageCount() == null ? 0 : conversation.getMessageCount(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
