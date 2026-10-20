package com.recordandgrow.retrieval.query;

import com.recordandgrow.config.ChatProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间窗解析规则测试。固定时钟为 2026-10-19（周一）12:30 +08:00。
 */
class TimeWindowParserTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-19T04:30:00Z"), ZONE);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T12:30:00+08:00");

    private ChatProperties properties;
    private TimeWindowParser parser;

    @BeforeEach
    void setUp() {
        properties = new ChatProperties();
        parser = new TimeWindowParser(properties, CLOCK, ZONE);
    }

    @Test
    void relativeDays() {
        assertWindow("今天做了什么", "2026-10-19T00:00:00+08:00", "2026-10-20T00:00:00+08:00");
        assertWindow("昨天呢", "2026-10-18T00:00:00+08:00", "2026-10-19T00:00:00+08:00");
        assertWindow("前天", "2026-10-17T00:00:00+08:00", "2026-10-18T00:00:00+08:00");
        assertWindow("明天", "2026-10-20T00:00:00+08:00", "2026-10-21T00:00:00+08:00");
    }

    @Test
    void relativeWeeks() {
        assertWindow("本周做了什么", "2026-10-19T00:00:00+08:00", "2026-10-26T00:00:00+08:00");
        assertWindow("上周做了什么", "2026-10-12T00:00:00+08:00", "2026-10-19T00:00:00+08:00");
        assertWindow("上周三", "2026-10-14T00:00:00+08:00", "2026-10-15T00:00:00+08:00");
        assertWindow("下周", "2026-10-26T00:00:00+08:00", "2026-11-02T00:00:00+08:00");
    }

    @Test
    void relativeMonths() {
        assertWindow("本月做了什么", "2026-10-01T00:00:00+08:00", NOW.toString());
        assertWindow("上个月", "2026-09-01T00:00:00+08:00", "2026-10-01T00:00:00+08:00");
        assertWindow("下个月", "2026-11-01T00:00:00+08:00", "2026-12-01T00:00:00+08:00");
    }

    @Test
    void recentRangesIncludeToday() {
        assertWindow("最近3天", "2026-10-17T00:00:00+08:00", NOW.toString());
        assertWindow("近一周", "2026-10-13T00:00:00+08:00", NOW.toString());
        assertWindow("最近一个月", "2026-09-20T00:00:00+08:00", NOW.toString());
        assertWindow("最近 90 天", "2026-07-22T00:00:00+08:00", NOW.toString());
    }

    @Test
    void absoluteDates() {
        assertWindow("10月17日做了什么", "2026-10-17T00:00:00+08:00", "2026-10-18T00:00:00+08:00");
        assertWindow("2026-10-17", "2026-10-17T00:00:00+08:00", "2026-10-18T00:00:00+08:00");
        assertWindow("2026/10/17", "2026-10-17T00:00:00+08:00", "2026-10-18T00:00:00+08:00");
        assertWindow("2026年10月17日", "2026-10-17T00:00:00+08:00", "2026-10-18T00:00:00+08:00");
    }

    @Test
    void spacesBetweenDigitsAndDateUnitsAreIgnored() {
        assertWindow("2020 年 3 月 5 日", "2020-03-05T00:00:00+08:00", "2020-03-06T00:00:00+08:00");
        assertWindow("2020年 3月", "2020-03-01T00:00:00+08:00", "2020-04-01T00:00:00+08:00");
    }

    @Test
    void fullWidthDigitsAreNormalized() {
        assertWindow("２０２６年１０月１７日", "2026-10-17T00:00:00+08:00", "2026-10-18T00:00:00+08:00");
    }

    @Test
    void monthWithoutYearUsesCurrentMonthToDate() {
        assertWindow("10月做了什么", "2026-10-01T00:00:00+08:00", NOW.toString());
    }

    @Test
    void monthWithoutYearUsesMostRecentStartedMonth() {
        assertWindow("3月", "2026-03-01T00:00:00+08:00", "2026-04-01T00:00:00+08:00");
        assertWindow("12月", "2025-12-01T00:00:00+08:00", "2026-01-01T00:00:00+08:00");
    }

    @Test
    void relativeYears() {
        assertWindow("今年", "2026-01-01T00:00:00+08:00", NOW.toString());
        assertWindow("去年", "2025-01-01T00:00:00+08:00", "2026-01-01T00:00:00+08:00");
        assertWindow("明年", "2027-01-01T00:00:00+08:00", "2028-01-01T00:00:00+08:00");
        assertWindow("2025年", "2025-01-01T00:00:00+08:00", "2026-01-01T00:00:00+08:00");
        assertWindow("2026年", "2026-01-01T00:00:00+08:00", NOW.toString());
        assertWindow("去年10月", "2025-10-01T00:00:00+08:00", "2025-11-01T00:00:00+08:00");
    }

    @Test
    void unsupportedSlashMonthDayFallsBackToDefault() {
        TimeWindow window = parser.parse("10/17 做了什么");

        assertThat(window.basis()).isEqualTo(TimeBasis.DEFAULT);
        assertThat(window.start()).isEqualTo(OffsetDateTime.parse("2026-09-20T00:00:00+08:00"));
        assertThat(window.end()).isEqualTo(NOW);
    }

    @Test
    void missingTimeExpressionUsesDefaultWindow() {
        TimeWindow window = parser.parse("我最近在忙什么");

        assertThat(window.basis()).isEqualTo(TimeBasis.DEFAULT);
        assertThat(window.start()).isEqualTo(OffsetDateTime.parse("2026-09-20T00:00:00+08:00"));
        assertThat(window.end()).isEqualTo(NOW);
    }

    @Test
    void anaphoraFallsBackToDefault() {
        assertThat(parser.parse("那第二天呢").basis()).isEqualTo(TimeBasis.DEFAULT);
        // 「上周那几天」仍能识别出「上周」，比回落默认窗口更贴近意图
        assertWindow("上周那几天", "2026-10-12T00:00:00+08:00", "2026-10-19T00:00:00+08:00");
    }

    @Test
    void windowLongerThanOneYearIsClamped() {
        TimeWindow window = parser.parse("最近500天");

        assertThat(window.clamped()).isTrue();
        assertThat(window.end()).isEqualTo(NOW);
        assertThat(window.start()).isEqualTo(NOW.minusDays(365));
    }

    @Test
    void onlyFirstTimeExpressionIsUsed() {
        // 最早出现的是「昨天」，后面的「上周」不再参与
        assertWindow("昨天和上周我都做了什么", "2026-10-18T00:00:00+08:00", "2026-10-19T00:00:00+08:00");
    }

    @Test
    void twoWeeksAgoWeekdayIsParsedAsTwoWeeksAgo() {
        // 2026-10-19 是周一，两周前的周一是 10-05，周三是 10-07
        assertWindow("上上周三", "2026-10-07T00:00:00+08:00", "2026-10-08T00:00:00+08:00");
        // 更长的重复形式回落，不能退化成「上上周三」
        assertThat(parser.parse("上上上周三").basis()).isEqualTo(TimeBasis.DEFAULT);
    }

    @Test
    void twoWeeksAgoVariantWithGeIsNotThisWeek() {
        // 「上上个周」是「上上周」的变体，不能落到本周
        assertWindow("上上个周", "2026-10-05T00:00:00+08:00", "2026-10-12T00:00:00+08:00");
    }

    @Test
    void twoMonthsAgoIsParsedAsTwoMonthsAgo() {
        assertWindow("上上个月", "2026-08-01T00:00:00+08:00", "2026-09-01T00:00:00+08:00");
    }

    @Test
    void unsupportedRepeatedTimeWordsFallBackToDefault() {
        // 「前前天」不命中「前天」
        TimeWindow day = parser.parse("前前天做了什么");
        assertThat(day.basis()).isEqualTo(TimeBasis.DEFAULT);
        assertThat(day.start()).isEqualTo(OffsetDateTime.parse("2026-09-20T00:00:00+08:00"));

        // 「上上周末」不命中「上上周」整周
        assertThat(parser.parse("上上周末").basis()).isEqualTo(TimeBasis.DEFAULT);
    }

    @Test
    void dayBeforeYesterdayIsSupported() {
        assertWindow("大前天", "2026-10-16T00:00:00+08:00", "2026-10-17T00:00:00+08:00");
    }

    @Test
    void windowAtMonthOrYearStartIsValid() {
        // 2026-11-01 00:00:00+08:00，「本月」的 start == now
        TimeWindow month = parserAt("2026-10-31T16:00:00Z").parse("本月");
        assertThat(month.start()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00+08:00"));
        assertThat(month.end()).isEqualTo(month.start().plusSeconds(1));

        // 2026-01-01 00:00:00+08:00，「今年」的 start == now
        TimeWindow year = parserAt("2025-12-31T16:00:00Z").parse("今年");
        assertThat(year.start()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+08:00"));
        assertThat(year.end()).isEqualTo(year.start().plusSeconds(1));

        TimeWindow recent = parserAt("2026-10-31T16:00:00Z").parse("最近1天");
        assertThat(recent.start()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00+08:00"));
        assertThat(recent.end()).isEqualTo(recent.start().plusSeconds(1));
    }

    private void assertWindow(String question, String start, String end) {
        TimeWindow window = parser.parse(question);
        assertThat(window.start()).as(question).isEqualTo(OffsetDateTime.parse(start));
        assertThat(window.end()).as(question).isEqualTo(OffsetDateTime.parse(end));
    }

    private static TimeWindowParser parserAt(String instant) {
        return new TimeWindowParser(new ChatProperties(), Clock.fixed(Instant.parse(instant), ZONE), ZONE);
    }
}
