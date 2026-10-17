package com.recordandgrow.timeline.dto;

import com.recordandgrow.timeline.TimelineEntry;

import java.time.OffsetDateTime;

/**
 * 时间线响应体。只暴露对外字段，不暴露 {@code deleted} 等内部字段。
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
        resp.startTime = entry.getStartTime();
        resp.endTime = entry.getEndTime();
        resp.createdAt = entry.getCreatedAt();
        resp.updatedAt = entry.getUpdatedAt();
        return resp;
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
