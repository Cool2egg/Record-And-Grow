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

    public record Start(String requestId, Long conversationId) {

        /** 模块四的 1 参构造：无会话（单轮链路）。 */
        public Start(String requestId) {
            this(requestId, null);
        }
    }

    /**
     * 检索过程中的一步（模块十三 §5.1）。
     *
     * <p>只描述「调了什么、查到几条、花了多久」，不携带模型思维链或 Prompt。
     * 顺序约束：全部 {@code step} 都在 {@code sources} 之前发出。
     *
     * @param seq   本次请求内递增的序号，从 1 开始
     * @param tool  工具名，例如 {@code search_timeline}
     * @param query 工具入参（最多 120 字符）
     * @param hits  工具返回的条数；失败为 0
     * @param ok    工具是否成功
     * @param ms    耗时毫秒
     * @param error 失败时的简短原因；成功为 {@code null}
     */
    public record Step(int seq, String tool, String query, int hits,
                       boolean ok, long ms, String error) {
    }

    /**
     * 检索来源。固定在 {@code start} 之后、第一个 {@code delta} 之前发送一次。
     *
     * <p>不变式：{@code items.length == retrieval.included + retrieval.documentIncluded}
     * 且等于 {@code retrieval.totalIncluded}。时间线在前（{@code T1..Tn}），
     * 文档在后（{@code D1..Dn}），编号与正文引用一一对应。
     *
     * @param items     最终纳入 Prompt 的来源（时间线 + 文档）
     * @param retrieval 检索元信息（窗口、数量、截断原因、文档检索状态）
     */
    public record Sources(List<SourceItem> items, RetrievalMeta retrieval) {

        public Sources {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 一条来源。{@code ref} 与正文里的 {@code [Tn]} / {@code [Dn]} 一一对应。
     *
     * <p>时间线：{@code type=timeline}，{@code id} 是时间线 id，新字段为 {@code null}；
     * 文档：{@code type=document}，{@code id} 是 documentId（前端据此跳详情），
     * {@code chunkId} 是具体块，{@code startTime/endTime} 恒为 {@code null}——
     * 伪造时间会让文档看起来像用户经历。字段顺序见设计稿 10.1。
     *
     * @param chunkId 文档块 id；时间线为 {@code null}
     * @param title   文档标题；时间线、记忆为 {@code null}（记忆类型见 {@code memoryType}）
     * @param heading 文档面包屑；可能为 {@code null}
     * @param score   文档相似度（6 位小数）；记忆为置信度；时间线为 {@code null}
     * @param memoryType 记忆类型 FACT / INFERRED；非记忆为 {@code null}
     * @param documentId 图谱边的出处文档 id（可跳详情）；时间线、文档、记忆为 {@code null}
     */
    public record SourceItem(
            String ref,
            String type,
            Long id,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String excerpt,
            Long chunkId,
            String title,
            String heading,
            Double score,
            String memoryType,
            Long documentId) {

        /** 模块十三之前的 10 参构造：新字段填 {@code null}。 */
        public SourceItem(String ref, String type, Long id, OffsetDateTime startTime,
                          OffsetDateTime endTime, String excerpt, Long chunkId, String title,
                          String heading, Double score) {
            this(ref, type, id, startTime, endTime, excerpt, chunkId, title, heading, score,
                    null, null);
        }

        /** 模块四的 6 参构造：新字段填 {@code null}，旧代码与旧测试零改动。 */
        public SourceItem(String ref, String type, Long id, OffsetDateTime startTime,
                          OffsetDateTime endTime, String excerpt) {
            this(ref, type, id, startTime, endTime, excerpt, null, null, null, null,
                    null, null);
        }
    }

    /**
     * 检索元信息。
     *
     * <p>{@code matched/retrieved/included/limitTruncated/budgetTruncated} 始终是**时间线**口径，
     * 语义与模块四一致；文档、记忆、图谱计数单独用各自字段表达。
     *
     * <p>不变式：{@code totalIncluded == included + documentIncluded + memoryIncluded + graphIncluded}
     * 且等于 {@code items.length}。
     *
     * @param documentSearched    文档 SQL 是否真的执行过：降级与未检索为 {@code false}，0 条命中也是 {@code true}
     * @param documentsRetrieved  阈值/配额后的文档块数（≤topK）；降级与未检索为 0
     * @param documentIncluded    最终进 Prompt 的文档块数
     * @param totalIncluded       {@code included + documentIncluded + memoryIncluded + graphIncluded}
     * @param documentsDegraded   是否降级；为 {@code true} 时 {@code documentSearched} 必为 {@code false}
     * @param degradeReason       降级原因枚举名；未降级为 {@code null}
     * @param memoryIncluded      纳入的长期记忆条数（Agent 路径）
     * @param graphIncluded       纳入的图谱三元组条数（Agent 路径）
     */
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
            boolean keywordFallback,
            boolean documentSearched,
            int documentsRetrieved,
            int documentIncluded,
            int totalIncluded,
            boolean documentsDegraded,
            String degradeReason,
            int memoryIncluded,
            int graphIncluded) {

        public RetrievalMeta {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        /** 模块四/六的 17 参构造：记忆与图谱计数填 0。 */
        public RetrievalMeta(OffsetDateTime windowStart,
                             OffsetDateTime windowEnd,
                             String timeBasis,
                             boolean clamped,
                             long matched,
                             int retrieved,
                             int included,
                             boolean limitTruncated,
                             boolean budgetTruncated,
                             List<String> keywords,
                             boolean keywordFallback,
                             boolean documentSearched,
                             int documentsRetrieved,
                             int documentIncluded,
                             int totalIncluded,
                             boolean documentsDegraded,
                             String degradeReason) {
            this(windowStart, windowEnd, timeBasis, clamped, matched, retrieved, included,
                    limitTruncated, budgetTruncated, keywords, keywordFallback,
                    documentSearched, documentsRetrieved, documentIncluded, totalIncluded,
                    documentsDegraded, degradeReason, 0, 0);
        }

        /**
         * 模块四的 11 参构造：新字段填「未检索」，{@code totalIncluded} 取旧 {@code included}，
         * 保证旧测试与旧客户端自洽。
         */
        public RetrievalMeta(OffsetDateTime windowStart,
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
            this(windowStart, windowEnd, timeBasis, clamped, matched, retrieved, included,
                    limitTruncated, budgetTruncated, keywords, keywordFallback,
                    false, 0, 0, included, false, null);
        }
    }

    public record Delta(String content) {
    }

    public record Done(String finishReason, ChatUsage usage) {
    }

    public record Error(int code, String errorType, String message, boolean partial, String requestId) {
    }
}
