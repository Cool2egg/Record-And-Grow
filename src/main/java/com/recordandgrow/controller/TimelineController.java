package com.recordandgrow.controller;

import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.ApiResponse;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.timeline.TimelineEntryService;
import com.recordandgrow.timeline.dto.TimelineEntryRequest;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 时间线接口。成功统一走 {@link ApiResponse} + HTTP 200（沿用骨架约定），
 * 失败由 {@code GlobalExceptionHandler} 转成 4xx/5xx。
 */
@RestController
@RequestMapping("/api/timeline")
public class TimelineController {

    private final TimelineEntryService timelineEntryService;

    public TimelineController(TimelineEntryService timelineEntryService) {
        this.timelineEntryService = timelineEntryService;
    }

    @PostMapping
    public ApiResponse<TimelineEntryResponse> create(@Valid @RequestBody TimelineEntryRequest request) {
        return ApiResponse.success(timelineEntryService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<TimelineEntryResponse> get(@PathVariable Long id) {
        return ApiResponse.success(timelineEntryService.get(id));
    }

    @GetMapping
    public ApiResponse<PageResult<TimelineEntryResponse>> list(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(timelineEntryService.list(
                parseTime(start, "start"), parseTime(end, "end"), page, size));
    }

    @PutMapping("/{id}")
    public ApiResponse<TimelineEntryResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody TimelineEntryRequest request) {
        return ApiResponse.success(timelineEntryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        timelineEntryService.delete(id);
        return ApiResponse.success();
    }

    /**
     * 解析 ISO-8601 带时区偏移的时间串（如 {@code 2026-10-14T00:00:00+08:00}）。
     * 为空则返回 null（不设该边界）。
     */
    private OffsetDateTime parseTime(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    name + " 时间格式错误，应为 ISO-8601（如 2026-10-14T00:00:00+08:00）");
        }
    }
}
