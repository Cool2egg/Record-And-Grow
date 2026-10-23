package com.recordandgrow.retrieval;

import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;

import java.util.List;

/**
 * 一次时间线检索的结果。
 *
 * <p>三个数量分开表达「在哪一步变少」：
 * <ul>
 *   <li>{@code matched}：SQL 命中总数（关键词生效时是关键词命中数）；</li>
 *   <li>{@code retrieved}：实际取回条数，受单次上限 100 限制；</li>
 *   <li>{@code included} 不在这里，由 ContextAssembler 按预算决定。</li>
 * </ul>
 */
public record RetrievalResult(
        List<TimelineEntryResponse> items,
        TimeWindow window,
        long matched,
        int retrieved,
        boolean limitTruncated,
        List<String> keywords,
        boolean keywordFallback) {

    public RetrievalResult {
        items = List.copyOf(items);
        keywords = List.copyOf(keywords);
    }

    /**
     * 未检索：路由判定只查文档时，时间线一路用这个占位，Prompt 里的窗口与计数仍然是真实的。
     */
    public static RetrievalResult empty(TimeWindow window, List<String> keywords) {
        return new RetrievalResult(List.of(), window, 0, 0, false,
                keywords == null ? List.of() : keywords, false);
    }
}
