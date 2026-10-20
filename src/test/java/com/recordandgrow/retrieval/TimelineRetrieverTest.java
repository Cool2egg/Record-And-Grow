package com.recordandgrow.retrieval;

import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.retrieval.query.TimeBasis;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.timeline.TimelineEntryService;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimelineRetrieverTest {

    private static final TimeWindow WINDOW = new TimeWindow(
            OffsetDateTime.parse("2026-10-12T00:00:00+08:00"),
            OffsetDateTime.parse("2026-10-19T00:00:00+08:00"),
            TimeBasis.RELATIVE_WEEK,
            false);

    @Mock
    private TimelineEntryService service;

    @InjectMocks
    private TimelineRetriever retriever;

    @Test
    void withoutKeywordsQueriesOnceAndReportsCounts() {
        when(service.search(any(), any(), eq(List.of()), anyInt()))
                .thenReturn(PageResult.of(List.of(entry(1)), 1, 1, 100));

        RetrievalResult result = retriever.retrieve(WINDOW, List.of());

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.retrieved()).isEqualTo(1);
        assertThat(result.limitTruncated()).isFalse();
        assertThat(result.keywordFallback()).isFalse();
        verify(service, times(1)).search(any(), any(), eq(List.of()), anyInt());
    }

    @Test
    void limitTruncationIsReportedSeparately() {
        List<TimelineEntryResponse> items = List.of(entry(1), entry(2));
        when(service.search(any(), any(), eq(List.of()), anyInt()))
                .thenReturn(PageResult.of(items, 150, 1, 100));

        RetrievalResult result = retriever.retrieve(WINDOW, List.of());

        assertThat(result.matched()).isEqualTo(150);
        assertThat(result.retrieved()).isEqualTo(2);
        assertThat(result.limitTruncated()).isTrue();
    }

    @Test
    void zeroHitWithKeywordsRetriesOnceWithoutKeywords() {
        when(service.search(any(), any(), eq(List.of("Go")), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 1, 100));
        when(service.search(any(), any(), eq(List.of()), anyInt()))
                .thenReturn(PageResult.of(List.of(entry(9)), 1, 1, 100));

        RetrievalResult result = retriever.retrieve(WINDOW, List.of("Go"));

        assertThat(result.keywordFallback()).isTrue();
        assertThat(result.retrieved()).isEqualTo(1);
        assertThat(result.keywords()).containsExactly("Go");
        verify(service, times(1)).search(any(), any(), eq(List.of("Go")), anyInt());
        verify(service, times(1)).search(any(), any(), eq(List.of()), anyInt());
    }

    @Test
    void keywordHitDoesNotFallback() {
        when(service.search(any(), any(), eq(List.of("Go", "Java")), anyInt()))
                .thenReturn(PageResult.of(List.of(entry(1)), 1, 1, 100));

        RetrievalResult result = retriever.retrieve(WINDOW, List.of("Go", "Java"));

        assertThat(result.keywordFallback()).isFalse();
        verify(service, times(1)).search(any(), any(), eq(List.of("Go", "Java")), anyInt());
    }

    private static TimelineEntryResponse entry(long id) {
        TimelineEntryResponse response = new TimelineEntryResponse();
        response.setId(id);
        response.setContent("记录 " + id);
        response.setStartTime(OffsetDateTime.parse("2026-10-18T20:00:00+08:00"));
        return response;
    }
}
