package com.recordandgrow.memory.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordandgrow.memory.Memory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆响应。evidence 反序列化成结构化列表，解析失败时返回空列表而不是 500。
 *
 * @param proposedType 仅 PENDING 有意义：确认后将落入的类型
 */
public record MemoryResponse(
        Long id,
        String type,
        String proposedType,
        String content,
        List<MemoryEvidence> evidence,
        String source,
        Double confidence,
        Long conversationId,
        OffsetDateTime confirmedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static MemoryResponse from(Memory memory) {
        return new MemoryResponse(
                memory.getId(),
                memory.getType(),
                memory.getProposedType(),
                memory.getContent(),
                parseEvidence(memory.getEvidence()),
                memory.getSource(),
                memory.getConfidence(),
                memory.getConversationId(),
                memory.getConfirmedAt(),
                memory.getCreatedAt(),
                memory.getUpdatedAt());
    }

    private static List<MemoryEvidence> parseEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<MemoryEvidence>>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
