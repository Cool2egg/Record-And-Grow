package com.recordandgrow.retrieval;

import com.recordandgrow.retrieval.document.DocumentRetrievalResult;

/**
 * 一次提问的检索结果：两路各自完整的结果对象 + 各自耗时。
 *
 * <p>两路**不合并、不互相排序**：时间线是用户事实，文档是外部知识，语义层级不同。
 * 合并只会让两类证据挤占彼此的预算（sources 里的 {@code T*} / {@code D*} 编号本来就分开）。
 * 融合是文档侧内部的事（向量 + 关键词），见模块九。
 *
 * @param timeline      时间线检索结果，含窗口与关键词回退标记
 * @param documents     文档检索结果，三态由 {@link DocumentRetrievalResult} 自己保证
 * @param timelineMs    时间线一路的耗时（毫秒），只用于日志
 * @param documentMs    文档一路的耗时（毫秒），只用于日志
 */
public record RetrievalBundle(
        RetrievalResult timeline,
        DocumentRetrievalResult documents,
        long timelineMs,
        long documentMs) {
}
