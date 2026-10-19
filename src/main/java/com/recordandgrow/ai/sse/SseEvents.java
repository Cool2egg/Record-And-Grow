package com.recordandgrow.ai.sse;

import com.recordandgrow.ai.ChatUsage;

/**
 * SSE 事件载荷。每个 data 都是 JSON。
 */
public final class SseEvents {

    private SseEvents() {
    }

    public record Start(String requestId) {
    }

    public record Delta(String content) {
    }

    public record Done(String finishReason, ChatUsage usage) {
    }

    public record Error(int code, String errorType, String message, boolean partial, String requestId) {
    }
}
