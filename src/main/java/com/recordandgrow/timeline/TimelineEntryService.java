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
        applyOverlap(wrapper, start, end);
        wrapper.orderByDesc(TimelineEntry::getStartTime)
                .orderByDesc(TimelineEntry::getId);

        Page<TimelineEntry> result = mapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<TimelineEntryResponse> items = result.getRecords().stream()
                .map(TimelineEntryResponse::from)
                .toList();
        return PageResult.of(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 检索用查询：时间窗重叠 + 可选关键词（AND），按 {@code start_time} 倒序，最多 {@code limit} 条。
     *
     * <p>关键词规则：首尾都是 ASCII 单词字符的纯 ASCII 词用 PostgreSQL 正则的词边界
     * （{@code ~* '\m...\M'}），避免「Go」命中「Google」；其他（中文、{@code C++} 等
     * 以符号结尾的词）用 {@code ILIKE} 子串。LIKE 的 {@code % _ \} 与正则元字符都会转义。
     * 空列表表示不过滤。
     *
     * <p>只读；软删仍由 {@code @TableLogic} 自动排除，区间语义与 {@link #list} 完全一致。
     */
    public PageResult<TimelineEntryResponse> search(OffsetDateTime start, OffsetDateTime end,
                                                    List<String> keywords, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));

        LambdaQueryWrapper<TimelineEntry> wrapper = new LambdaQueryWrapper<>();
        applyOverlap(wrapper, start, end);
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                String trimmed = keyword.trim();
                if (isAsciiWordBounded(trimmed)) {
                    wrapper.apply("content ~* {0}", "\\m" + escapeRegex(trimmed) + "\\M");
                } else {
                    wrapper.apply("content ILIKE {0}", "%" + escapeLike(trimmed) + "%");
                }
            }
        }
        wrapper.orderByDesc(TimelineEntry::getStartTime)
                .orderByDesc(TimelineEntry::getId);

        Page<TimelineEntry> result = mapper.selectPage(new Page<>(1, safeLimit), wrapper);
        List<TimelineEntryResponse> items = result.getRecords().stream()
                .map(TimelineEntryResponse::from)
                .toList();
        return PageResult.of(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private static void applyOverlap(LambdaQueryWrapper<TimelineEntry> wrapper,
                                     OffsetDateTime start, OffsetDateTime end) {
        if (end != null) {
            wrapper.lt(TimelineEntry::getStartTime, end);
        }
        if (start != null) {
            wrapper.and(w -> w.isNull(TimelineEntry::getEndTime)
                    .or()
                    .gt(TimelineEntry::getEndTime, start));
        }
    }

    /**
     * 只有「首尾都是 ASCII 单词字符」的词才用 {@code \m...\M}。
     * 像 {@code C++}、{@code C#}、{@code .NET} 这样以符号结尾/开头的词，
     * 词边界断言不会命中，退回 ILIKE 子串。
     */
    private static boolean isAsciiWordBounded(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c < 128)) {
            return false;
        }
        return isWordChar(value.charAt(0)) && isWordChar(value.charAt(value.length() - 1));
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static String escapeRegex(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        String meta = "\\^$.|?*+()[]{}";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (meta.indexOf(c) >= 0) {
                builder.append('\\');
            }
            builder.append(c);
        }
        return builder.toString();
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
