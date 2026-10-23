package com.recordandgrow.memory.dto;

import com.recordandgrow.memory.Message;

import java.time.OffsetDateTime;

/**
 * 会话消息响应。sources 是原样 JSON 字符串，前端按需解析。
 */
public record MessageResponse(
        Long id,
        String role,
        String content,
        String sources,
        int tokenCount,
        OffsetDateTime createdAt) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getSources(),
                message.getTokenCount() == null ? 0 : message.getTokenCount(),
                message.getCreatedAt());
    }
}
