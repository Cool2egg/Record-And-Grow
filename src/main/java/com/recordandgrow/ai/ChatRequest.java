package com.recordandgrow.ai;

import java.util.List;

/**
 * 与供应商无关的对话请求。
 *
 * <p>不包含 {@code model}、{@code thinking} 等供应商字段：这些属于客户端配置，
 * 不能泄漏到业务层。
 */
public record ChatRequest(List<ChatMessage> messages, Integer maxTokens, Double temperature) {

    public ChatRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        messages = List.copyOf(messages);
    }
}
