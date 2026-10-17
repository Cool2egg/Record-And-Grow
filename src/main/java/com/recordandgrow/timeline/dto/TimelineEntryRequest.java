package com.recordandgrow.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 时间线新建/更新请求体。创建与全量更新字段一致，故共用一个 DTO。
 *
 * <p>{@code endTime} 可空（空 = 进行中/未结束）；{@code endTime >= startTime} 的
 * 跨字段校验在 Service 层做（返回统一的 400 业务异常），不在 Bean Validation 里，避免
 * 属性级错误在全局异常处理器里被漏掉。
 */
public class TimelineEntryRequest {

    @NotBlank(message = "content 不能为空")
    @Size(max = 10000, message = "content 长度不能超过 10000")
    private String content;

    @NotNull(message = "startTime 不能为空")
    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

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
}
