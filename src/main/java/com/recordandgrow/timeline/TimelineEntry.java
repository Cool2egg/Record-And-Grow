package com.recordandgrow.timeline;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 时间线事实实体，对应表 timeline_entry。
 *
 * <p>用户主动记录的事件，内容视为事实。字段职责：
 * <ul>
 *   <li>{@code content} / {@code startTime} / {@code endTime}：用户录入的事实（由用户决定）；</li>
 *   <li>{@code createdAt} / {@code updatedAt}：记录元数据（由系统自动填充，客户端不可改）；</li>
 *   <li>{@code deleted}：软删标记（{@link TableLogic}），删除后仍保留数据、查询自动排除。</li>
 * </ul>
 *
 * <p>{@code endTime} 为空表示「进行中/未结束」；瞬时事件用 start = end 表示。
 */
@TableName("timeline_entry")
public class TimelineEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String content;

    private OffsetDateTime startTime;

    /**
     * 结束时刻，null 表示进行中。
     *
     * <p>{@code updateStrategy = ALWAYS}：PUT 是全量更新，必须能把 end_time 从有值改为 null；
     * MyBatis-Plus 默认跳过 null 字段，不加这个注解会导致「清空结束时间」静默失效。
     * 注意：这也意味着任何 {@code updateById} 都必须显式带上 endTime，否则会被写成 null。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;

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

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
