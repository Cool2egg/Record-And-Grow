package com.recordandgrow.ai.sse;

import com.recordandgrow.ai.ChatUsage;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * SSE 事件载荷。每个 data 都是 JSON。
 */
public final class SseEvents {

    private SseEvents() {
    }

    public record Start(String requestId) {
    }

    /**
     * 检索来源。固定在 {@code start} 之后、第一个 {@code delta} 之前发送一次。
     *
     * @param items     最终纳入 Prompt 的来源，长度等于 {@code retrieval.included}
     * @param retrieval 检索元信息（窗口、数量、截断原因）
     */
    public record Sources(List<SourceItem> items, RetrievalMeta retrieval) {

        public Sources {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 一条来源。{@code ref} 与正文里的 {@code [Tn]} 一一对应；
     * {@code type} 为以后接入文档/记忆来源预留。
     */
    public record SourceItem(
            String ref,
            String type,
            Long id,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String excerpt) {
    }

    public record RetrievalMeta(
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            String timeBasis,
            boolean clamped,
            long matched,
            int retrieved,
            int included,
            boolean limitTruncated,
            boolean budgetTruncated,
            List<String> keywords,
            boolean keywordFallback) {

        public RetrievalMeta {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record Delta(String content) {
    }

    public record Done(String finishReason, ChatUsage usage) {
    }

    public record Error(int code, String errorType, String message, boolean partial, String requestId) {
    }
}
