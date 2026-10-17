package com.recordandgrow.ai.dto;

import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Locale;

/**
 * AI 自检接口的对话请求。
 */
public record AiChatRequest(
        @NotEmpty @Size(max = 100) List<@Valid AiMessage> messages,
        @Min(1) @Max(384000) Integer maxTokens,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature) {

    public ChatRequest toDomain() {
        return new ChatRequest(
                messages.stream().map(AiMessage::toDomain).toList(),
                maxTokens,
                temperature);
    }

    public record AiMessage(@NotBlank String role, @NotBlank String content) {

        ChatMessage toDomain() {
            return new ChatMessage(parseRole(role), content);
        }

        private static ChatMessage.Role parseRole(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "system" -> ChatMessage.Role.SYSTEM;
                case "user" -> ChatMessage.Role.USER;
                case "assistant" -> ChatMessage.Role.ASSISTANT;
                default -> throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "role 必须是 system / user / assistant");
            };
        }
    }
}
