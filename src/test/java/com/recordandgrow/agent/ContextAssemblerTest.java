package com.recordandgrow.agent;

import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.retrieval.query.TimeBasis;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T12:30:00+08:00");
    private static final String QUESTION = "我做了什么";

    private ChatProperties properties;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new ChatProperties();
        assembler = new ContextAssembler(properties, ZONE);
    }

    @Test
    void assignsContiguousRefsAndMatchesSources() {
        List<TimelineEntryResponse> items = List.of(
                entry(2, "学习 SSE", "2026-10-18T20:00:00+08:00", null),
                entry(1, "跑步 5km", "2026-10-17T07:00:00+08:00", "2026-10-17T07:30:00+08:00"));

        ContextBundle bundle = assembler.assemble(result(items), QUESTION, NOW);

        assertThat(bundle.includedCount()).isEqualTo(2);
        assertThat(bundle.sources().items()).extracting("ref").containsExactly("T1", "T2");
        assertThat(bundle.sources().items()).extracting("id").containsExactly(2L, 1L);
        assertThat(bundle.sources().retrieval().included()).isEqualTo(2);
        // sources 与 Prompt/窗口统一用聊天时区，不直接透传 UTC
        assertThat(bundle.sources().items().get(0).startTime().getOffset())
                .isEqualTo(ZoneOffset.ofHours(8));
        assertThat(bundle.sources().items().get(1).endTime().getOffset())
                .isEqualTo(ZoneOffset.ofHours(8));

        String prompt = userPrompt(bundle);
        assertThat(prompt).contains("[T1] 2026-10-18 20:00 ~ 进行中｜学习 SSE");
        assertThat(prompt).contains("[T2] 2026-10-17 07:00 ~ 2026-10-17 07:30｜跑步 5km");
        assertRefsMatchSources(prompt, bundle);
    }

    @Test
    void longEntryIsTruncatedBeforeBeingDropped() {
        String content = "字".repeat(2000);
        ContextBundle bundle = assembler.assemble(
                result(List.of(entry(1, content, "2026-10-18T20:00:00+08:00", null))), QUESTION, NOW);

        String prompt = userPrompt(bundle);
        assertThat(prompt).contains("已截断，原文 2000 字");
        assertThat(bundle.sources().items().get(0).excerpt()).endsWith("…");
        assertThat(bundle.sources().items().get(0).excerpt().length()).isEqualTo(121);
    }

    @Test
    void truncatesToSecondTierBeforeDroppingWholeEntries() {
        List<TimelineEntryResponse> items = List.of(
                entry(2, "a".repeat(1000), "2026-10-18T20:00:00+08:00", null),
                entry(1, "b".repeat(1000), "2026-10-17T20:00:00+08:00", null));

        properties.setMaxContextChars(100_000);
        ContextBundle full = assembler.assemble(result(items), QUESTION, NOW);
        assertThat(full.includedCount()).isEqualTo(2);

        // 预算比 "两条各 500 字" 少 250，但比 "两条各 300 字" 多
        properties.setMaxContextChars(full.promptChars() - 250);
        ContextBundle reduced = assembler.assemble(result(items), QUESTION, NOW);

        assertThat(reduced.includedCount()).as("应先把单条截到 300，而不是直接丢一条").isEqualTo(2);
        assertThat(userPrompt(reduced)).contains("原文 1000 字");
    }

    @Test
    void multiDayWindowDropsOldestEntries() {
        properties.setMaxEntries(3);
        List<TimelineEntryResponse> items = List.of(
                entry(5, "周五", "2026-10-18T20:00:00+08:00", null),
                entry(4, "周四", "2026-10-17T20:00:00+08:00", null),
                entry(3, "周三", "2026-10-16T20:00:00+08:00", null),
                entry(2, "周二", "2026-10-15T20:00:00+08:00", null),
                entry(1, "周一", "2026-10-14T20:00:00+08:00", null));

        ContextBundle bundle = assembler.assemble(result(items), QUESTION, NOW);

        assertThat(bundle.includedCount()).isEqualTo(3);
        assertThat(bundle.sources().items()).extracting("id").containsExactly(5L, 4L, 3L);
        assertThat(bundle.budgetTruncated()).isTrue();
        assertThat(bundle.droppedCount()).isEqualTo(2);
    }

    @Test
    void singleDayWindowSamplesEvenlyAndKeepsBothEnds() {
        properties.setMaxEntries(10);
        List<TimelineEntryResponse> items = sameDayEntries(50);

        ContextBundle bundle = assembler.assemble(result(items), QUESTION, NOW);

        assertThat(bundle.includedCount()).isEqualTo(10);
        assertThat(bundle.sources().items()).hasSize(10);

        OffsetDateTime first = bundle.sources().items().get(0).startTime();
        OffsetDateTime last = bundle.sources().items().get(9).startTime();
        assertThat(first.toLocalTime().getHour()).isEqualTo(22);
        assertThat(last.toLocalTime().getHour()).isZero();
    }

    @Test
    void noHitProducesEmptySourcesAndEmptyRecordsSection() {
        ContextBundle bundle = assembler.assemble(result(List.of()), QUESTION, NOW);

        assertThat(bundle.includedCount()).isZero();
        assertThat(bundle.sources().items()).isEmpty();
        assertThat(userPrompt(bundle)).contains("（无）");
        assertThat(userPrompt(bundle)).contains("命中 0 条，取回 0 条，纳入 0 条");
    }

    @Test
    void promptReportsLimitAndBudgetTruncationSeparately() {
        properties.setMaxEntries(1);
        List<TimelineEntryResponse> items = List.of(
                entry(2, "新", "2026-10-18T20:00:00+08:00", null),
                entry(1, "旧", "2026-10-17T20:00:00+08:00", null));
        RetrievalResult retrieval = new RetrievalResult(
                items,
                window(),
                5,
                2,
                true,
                List.of("Java"),
                true);

        ContextBundle bundle = assembler.assemble(retrieval, QUESTION, NOW);
        String prompt = userPrompt(bundle);

        assertThat(prompt).contains("命中 5 条，取回 2 条，纳入 1 条");
        assertThat(prompt).contains("3 条超出单次取回上限");
        assertThat(prompt).contains("1 条因上下文预算未纳入");
        assertThat(bundle.sources().retrieval().keywordFallback()).isTrue();
        assertThat(bundle.sources().retrieval().keywords()).containsExactly("Java");
    }

    @Test
    void recordBudgetIsCappedIndependentlyOfTotalBudget() {
        properties.setMaxContextChars(100_000);
        properties.setMaxRecordsChars(500);
        List<TimelineEntryResponse> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(entry(100 + i, "x".repeat(100),
                    "2026-10-18T" + String.format("%02d", i) + ":00:00+08:00", null));
        }

        ContextBundle bundle = assembler.assemble(result(items), QUESTION, NOW);

        assertThat(bundle.includedCount()).isBetween(1, 5);
        assertThat(bundle.budgetTruncated()).isTrue();
    }

    // ---- helpers ----

    private List<TimelineEntryResponse> sameDayEntries(int count) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm");
        List<TimelineEntryResponse> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalTime time = LocalTime.MIDNIGHT.plusMinutes(28L * (count - 1 - i));
            items.add(entry(1000 - i, "记录 " + i,
                    "2026-10-18T" + time.format(format) + ":00+08:00", null));
        }
        return items;
    }

    private static TimelineEntryResponse entry(long id, String content, String start, String end) {
        TimelineEntryResponse response = new TimelineEntryResponse();
        response.setId(id);
        response.setContent(content);
        response.setStartTime(OffsetDateTime.parse(start));
        response.setEndTime(end == null ? null : OffsetDateTime.parse(end));
        return response;
    }

    private static RetrievalResult result(List<TimelineEntryResponse> items) {
        return new RetrievalResult(items, window(), items.size(), items.size(), false, List.of(), false);
    }

    private static TimeWindow window() {
        return new TimeWindow(
                OffsetDateTime.parse("2026-10-12T00:00:00+08:00"),
                OffsetDateTime.parse("2026-10-19T00:00:00+08:00"),
                TimeBasis.RELATIVE_WEEK,
                false);
    }

    private static String userPrompt(ContextBundle bundle) {
        return bundle.messages().get(1).content();
    }

    private static void assertRefsMatchSources(String prompt, ContextBundle bundle) {
        Matcher matcher = Pattern.compile("\\[T(\\d+)]").matcher(prompt);
        List<Integer> refs = new ArrayList<>();
        while (matcher.find()) {
            refs.add(Integer.parseInt(matcher.group(1)));
        }
        assertThat(refs).isNotEmpty();
        for (int ref : refs) {
            assertThat(ref).isBetween(1, bundle.sources().items().size());
            assertThat(bundle.sources().items().get(ref - 1).ref()).isEqualTo("T" + ref);
        }
    }
}
