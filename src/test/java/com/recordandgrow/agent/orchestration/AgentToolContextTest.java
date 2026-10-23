package com.recordandgrow.agent.orchestration;

import com.recordandgrow.graph.GraphHit;
import com.recordandgrow.graph.GraphRetrievalResult;
import com.recordandgrow.memory.dto.MemoryResponse;
import com.recordandgrow.retrieval.document.DocumentHit;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.query.TimeBasis;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolContextTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final TimeWindow WINDOW = new TimeWindow(
            OffsetDateTime.parse("2026-10-15T00:00:00+08:00"),
            OffsetDateTime.parse("2026-10-22T00:00:00+08:00"),
            TimeBasis.DEFAULT, false);

    @Test
    void refsShouldBeStableAndOrderedBySection() {
        AgentToolContext context = new AgentToolContext(1L, WINDOW, List.of(), 20, ZONE);

        context.addDocuments(DocumentRetrievalResult.of(List.of(
                new DocumentHit(100L, 10L, "笔记", "第一节", "内容", 0.8, 0, 10, 0)), 0.8));
        context.addTimeline(new RetrievalResult(List.of(timeline(1L, "跑步")), WINDOW,
                1, 1, false, List.of(), false));
        context.addTimeline(new RetrievalResult(List.of(timeline(2L, "读书")), WINDOW,
                1, 1, false, List.of(), false));
        context.addMemories(List.of(memory(3L, "喜欢早起")));
        context.addGraph(GraphRetrievalResult.searched(List.of(
                new GraphHit(7L, "Kafka", "属于", "消息队列", 10L, "笔记"))));

        var sources = context.sources();
        assertThat(sources.items()).extracting("ref")
                .containsExactly("T1", "T2", "D1", "M1", "G1");
        assertThat(sources.retrieval().totalIncluded()).isEqualTo(5);
        assertThat(sources.retrieval().included()).isEqualTo(2);
        assertThat(sources.retrieval().documentIncluded()).isEqualTo(1);
        assertThat(sources.retrieval().memoryIncluded()).isEqualTo(1);
        assertThat(sources.retrieval().graphIncluded()).isEqualTo(1);
        assertThat(sources.retrieval().documentSearched()).isTrue();
    }

    @Test
    void evidenceBlockShouldGroupSectionsAndRespectBudget() {
        AgentToolContext context = new AgentToolContext(1L, WINDOW, List.of("跑步"), 20, ZONE);
        context.addTimeline(new RetrievalResult(List.of(timeline(1L, "早上跑步")), WINDOW,
                1, 1, false, List.of(), false));
        context.addMemories(List.of(memory(2L, "喜欢早起")));

        String block = context.evidenceBlock(2000);
        assertThat(block).contains("【时间线记录】").contains("[T1]").contains("早上跑步");
        assertThat(block).contains("【长期记忆】").contains("[M1]").contains("喜欢早起");

        String tiny = context.evidenceBlock(30);
        assertThat(tiny.length()).isLessThanOrEqualTo(40);
    }

    @Test
    void duplicateEvidenceShouldNotConsumeSecondRef() {
        AgentToolContext context = new AgentToolContext(1L, WINDOW, List.of(), 20, ZONE);
        context.addTimeline(new RetrievalResult(List.of(timeline(1L, "跑步")), WINDOW,
                1, 1, false, List.of(), false));
        context.addTimeline(new RetrievalResult(List.of(timeline(1L, "跑步")), WINDOW,
                1, 1, false, List.of(), false));

        assertThat(context.evidence()).hasSize(1);
        assertThat(context.evidence().get(0).ref()).isEqualTo("T1");
    }

    @Test
    void documentsShouldStillEnterWhenTimelineIsHuge() {
        AgentToolContext context = new AgentToolContext(1L, WINDOW, List.of(), 10, ZONE);
        java.util.List<TimelineEntryResponse> entries = new java.util.ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            entries.add(timeline(id, "记录 " + id));
        }
        context.addTimeline(new RetrievalResult(entries, WINDOW, entries.size(), entries.size(),
                false, List.of(), false));
        context.addDocuments(DocumentRetrievalResult.of(List.of(
                new DocumentHit(100L, 10L, "读书笔记", "第三章", "深度工作的方法", 0.9, 0, 10, 0)), 0.9));

        assertThat(context.evidence()).extracting(EvidenceItem::kind)
                .contains("document");
        assertThat(context.evidenceBlock(2000)).contains("【文档片段】").contains("[D1]");
    }

    private static TimelineEntryResponse timeline(Long id, String content) {
        TimelineEntryResponse entry = new TimelineEntryResponse();
        entry.setId(id);
        entry.setContent(content);
        entry.setStartTime(OffsetDateTime.parse("2026-10-20T07:00:00+08:00"));
        return entry;
    }

    private static MemoryResponse memory(Long id, String content) {
        return new MemoryResponse(id, "FACT", null, content, List.of(), "USER", 1.0,
                null, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }
}
