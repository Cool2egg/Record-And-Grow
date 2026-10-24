package com.recordandgrow.timeline.dto;

import com.recordandgrow.timeline.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应时间契约：对外统一 UTC，时刻本身不变。
 * 前端按浏览器本地时区渲染，后端只保证唯一表示。
 */
class TimelineEntryResponseTest {

    @Test
    void fromShouldKeepInstantAndUseUtcOffset() {
        TimelineEntry entry = new TimelineEntry();
        entry.setId(1L);
        entry.setContent("夜跑");
        entry.setStartTime(OffsetDateTime.parse("2026-10-20T21:30:00+08:00"));
        entry.setEndTime(OffsetDateTime.parse("2026-10-20T22:15:00+08:00"));
        entry.setCreatedAt(OffsetDateTime.parse("2026-10-20T22:20:00+08:00"));
        entry.setUpdatedAt(OffsetDateTime.parse("2026-10-20T22:21:00+08:00"));

        TimelineEntryResponse response = TimelineEntryResponse.from(entry);

        assertThat(response.getStartTime()).isEqualTo(entry.getStartTime());
        assertThat(response.getStartTime().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.getStartTime().toString()).isEqualTo("2026-10-20T13:30Z");
        assertThat(response.getEndTime().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.getUpdatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void fromShouldKeepNullEndTime() {
        TimelineEntry entry = new TimelineEntry();
        entry.setContent("进行中");
        entry.setStartTime(OffsetDateTime.parse("2026-10-20T21:30:00+08:00"));

        TimelineEntryResponse response = TimelineEntryResponse.from(entry);

        assertThat(response.getEndTime()).isNull();
        assertThat(response.getStartTime()).isNotNull();
    }
}
