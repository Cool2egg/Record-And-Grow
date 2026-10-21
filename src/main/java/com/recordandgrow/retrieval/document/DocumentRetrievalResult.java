package com.recordandgrow.retrieval.document;

import java.util.List;

/**
 * 一次文档检索的结果，只有三种**互斥**状态：
 *
 * <table>
 *   <caption>三态语义</caption>
 *   <tr><th>状态</th><th>searched</th><th>degraded</th><th>degradeReason</th></tr>
 *   <tr><td>未检索（关闭 / 没有 READY 文档）</td><td>false</td><td>false</td><td>null</td></tr>
 *   <tr><td>降级（有文档但查询 embedding 失败）</td><td>false</td><td>true</td><td>非空</td></tr>
 *   <tr><td>已检索（SQL 执行过，0 条也算）</td><td>true</td><td>false</td><td>null</td></tr>
 * </table>
 *
 * <p>{@code searched} 只表示「SQL 是否真的执行过」，与是否命中无关。降级时 SQL 不执行，
 * 所以是 {@code false}；客户端用 {@code searched=false + degraded=true} 区分
 * 「降级」与「查了没中」。
 *
 * <p>{@code topScore} 是**阈值过滤前**的最高相似度（空结果为 {@code null}），
 * 供日志与阈值校准使用，不随过滤/配额变化。
 */
public record DocumentRetrievalResult(
        List<DocumentHit> hits,
        boolean searched,
        boolean degraded,
        DocumentDegradeReason degradeReason,
        Double topScore) {

    public DocumentRetrievalResult {
        hits = List.copyOf(hits);
        if (degraded && (searched || degradeReason == null)) {
            throw new IllegalArgumentException("降级态必须 searched=false 且带原因");
        }
        if (!degraded && degradeReason != null) {
            throw new IllegalArgumentException("非降级态不允许带原因");
        }
    }

    /** 关闭 / 没有 READY 文档：没调 embedding，SQL 未执行。 */
    public static DocumentRetrievalResult notSearched() {
        return new DocumentRetrievalResult(List.of(), false, false, null, null);
    }

    /** 有 READY 文档但查询 embedding 失败：SQL 未执行，必须有可见标记。 */
    public static DocumentRetrievalResult degraded(DocumentDegradeReason reason) {
        return new DocumentRetrievalResult(List.of(), false, true, reason, null);
    }

    /** SQL 已执行；{@code hits} 为空表示没命中或全部低于阈值。 */
    public static DocumentRetrievalResult of(List<DocumentHit> hits, Double topScore) {
        return new DocumentRetrievalResult(hits, true, false, null, topScore);
    }
}
