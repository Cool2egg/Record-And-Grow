package com.recordandgrow.timeline;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 时间线事实表 Mapper。继承 {@link BaseMapper} 获得通用 CRUD。
 * 软删、分页分别由实体上的 {@code @TableLogic} 与全局分页插件处理，无需手写 SQL。
 */
@Mapper
public interface TimelineEntryMapper extends BaseMapper<TimelineEntry> {
}
