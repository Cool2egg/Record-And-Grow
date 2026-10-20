package com.recordandgrow.retrieval;

import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.timeline.TimelineEntryService;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 时间线检索：时间窗 + 可选关键词。
 *
 * <p>带关键词查询 0 命中时自动去掉关键词重查一次，避免抽取错误让整次提问失去上下文。
 * 回退只做一次，且不改变时间窗。
 */
@Component
public class TimelineRetriever {

    /** 与模块二的分页上限一致，避免两处配置漂移。 */
    static final int RETRIEVAL_LIMIT = 100;

    private final TimelineEntryService timelineEntryService;

    public TimelineRetriever(TimelineEntryService timelineEntryService) {
        this.timelineEntryService = timelineEntryService;
    }

    public RetrievalResult retrieve(TimeWindow window, List<String> keywords) {
        List<String> safeKeywords = keywords == null ? List.of() : List.copyOf(keywords);

        PageResult<TimelineEntryResponse> page = timelineEntryService.search(
                window.start(), window.end(), safeKeywords, RETRIEVAL_LIMIT);

        boolean fallback = false;
        if (!safeKeywords.isEmpty() && page.getTotal() == 0) {
            page = timelineEntryService.search(
                    window.start(), window.end(), List.of(), RETRIEVAL_LIMIT);
            fallback = true;
        }

        int retrieved = page.getItems().size();
        return new RetrievalResult(
                page.getItems(),
                window,
                page.getTotal(),
                retrieved,
                page.getTotal() > retrieved,
                safeKeywords,
                fallback);
    }
}
