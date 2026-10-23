package com.recordandgrow.memory.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建会话。标题可空，默认「新对话」；首条提问会自动补标题。
 */
public record ConversationCreateRequest(
        @Size(max = 200) String title) {
}
