package com.recordandgrow.ai;

import java.time.Duration;

/**
 * 可观测 hook。本模块只留接口，不接 Langfuse。
 */
public interface AiCallObserver {

    default void onChatComplete(String client, ChatUsage usage, Duration latency) {
    }

    default void onChatError(String client, AiErrorType type, Duration latency, int attempts) {
    }

    default void onStreamStart(String client) {
    }

    default void onStreamEnd(String client, ChatUsage usage, Duration latency, boolean cancelled) {
    }

    default void onEmbeddingComplete(int count, int dimensions, ChatUsage usage, Duration latency) {
    }
}
