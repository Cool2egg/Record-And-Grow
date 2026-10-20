package com.recordandgrow.retrieval.query;

import java.time.OffsetDateTime;

/**
 * 检索时间窗，半开区间 {@code [start, end)}，与模块二的区间重叠语义一致。
 *
 * @param basis   时间表达的类型
 * @param clamped 是否因超过 {@code max-window-days} 被夹紧
 */
public record TimeWindow(OffsetDateTime start, OffsetDateTime end, TimeBasis basis, boolean clamped) {

    public TimeWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("时间窗边界不能为空");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("时间窗 start 必须早于 end");
        }
    }
}
