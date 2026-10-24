package com.recordandgrow.timeline.dto;

import com.recordandgrow.timeline.TimelineEntry;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 时间线响应体。只暴露对外字段，不暴露 {@code deleted} 等内部字段。
 *
 * <p>响应中的时间统一转换为 UTC（序列化为 {@code Z}），避免同一个时刻因请求偏移不同
 * 而出现两种表示。客户端按需转换为本地时区展示。
 */
public class TimelineEntryResponse {

    private Long id;
    private String content;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static TimelineEntryResponse from(TimelineEntry entry) {
        TimelineEntryResponse resp = new TimelineEntryResponse();
        resp.id = entry.getId();
        resp.content = entry.getContent();
        resp.startTime = utc(entry.getStartTime());
        resp.endTime = utc(entry.getEndTime());
        resp.createdAt = utc(entry.getCreatedAt());
        resp.updatedAt = utc(entry.getUpdatedAt());
        return resp;
    }

    /** 保持时刻不变，只把偏移换成 UTC，确保对外表示唯一。 */
    private static OffsetDateTime utc(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public OffsetDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(OffsetDateTime startTime) {
        this.startTime = startTime;
    }

    public OffsetDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(OffsetDateTime endTime) {
        this.endTime = endTime;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
