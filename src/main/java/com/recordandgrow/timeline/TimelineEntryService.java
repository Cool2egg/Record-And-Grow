package com.recordandgrow.timeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.timeline.dto.TimelineEntryRequest;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 时间线业务逻辑：增删改查 + 按日期区间重叠查询。
 *
 * <p>用户主动录入的记录是事实，创建/更新直接保存；删除采用软删（{@code @TableLogic}），
 * 数据保留、查询自动排除。
 */
@Service
public class TimelineEntryService {

    /** 单次查询最大条数，防止误传过大分页。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final TimelineEntryMapper mapper;

    public TimelineEntryService(TimelineEntryMapper mapper) {
        this.mapper = mapper;
    }

    public TimelineEntryResponse create(TimelineEntryRequest request) {
        validateTimeRange(request);

        TimelineEntry entry = new TimelineEntry();
        entry.setContent(request.getContent());
        entry.setStartTime(request.getStartTime());
        entry.setEndTime(request.getEndTime());
        mapper.insert(entry);
        return TimelineEntryResponse.from(entry);
    }

    public TimelineEntryResponse get(Long id) {
        return TimelineEntryResponse.from(mustExist(id));
    }

    /**
     * 按日期区间查询，语义为「重叠」而非「落在窗口内」。
     *
     * <p>命中条件（半开区间 [start, end)）：{@code entry.start_time < end}
     * 且 {@code (entry.end_time IS NULL OR entry.end_time > start)}。
     * 跨天事件会命中其覆盖的每一天；end_time 为空的「进行中」事件会命中其开始之后的任意窗口。
     * 排序按 start_time 倒序（新在前）。
     */
    public PageResult<TimelineEntryResponse> list(OffsetDateTime start, OffsetDateTime end, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        LambdaQueryWrapper<TimelineEntry> wrapper = new LambdaQueryWrapper<>();
        if (end != null) {
            wrapper.lt(TimelineEntry::getStartTime, end);
        }
        if (start != null) {
            wrapper.and(w -> w.isNull(TimelineEntry::getEndTime)
                    .or()
                    .gt(TimelineEntry::getEndTime, start));
        }
        wrapper.orderByDesc(TimelineEntry::getStartTime)
                .orderByDesc(TimelineEntry::getId);

        Page<TimelineEntry> result = mapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<TimelineEntryResponse> items = result.getRecords().stream()
                .map(TimelineEntryResponse::from)
                .toList();
        return PageResult.of(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public TimelineEntryResponse update(Long id, TimelineEntryRequest request) {
        validateTimeRange(request);
        mustExist(id);

        TimelineEntry patch = new TimelineEntry();
        patch.setId(id);
        patch.setContent(request.getContent());
        patch.setStartTime(request.getStartTime());
        patch.setEndTime(request.getEndTime());
        // 只更新可编辑字段；createdAt/deleted 为空不会写入，updatedAt 由自动填充刷新
        mapper.updateById(patch);

        return get(id);
    }

    public void delete(Long id) {
        mustExist(id);
        // @TableLogic 会把 DELETE 改写为 UPDATE deleted = true（软删）
        mapper.deleteById(id);
    }

    private TimelineEntry mustExist(Long id) {
        TimelineEntry entry = mapper.selectById(id);
        if (entry == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "时间线记录不存在");
        }
        return entry;
    }

    private void validateTimeRange(TimelineEntryRequest request) {
        if (request.getEndTime() != null && request.getEndTime().isBefore(request.getStartTime())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "endTime 不能早于 startTime");
        }
    }
}
