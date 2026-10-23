package com.recordandgrow.controller;

import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.ApiResponse;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.memory.MemoryService;
import com.recordandgrow.memory.MemoryType;
import com.recordandgrow.memory.dto.MemoryCreateRequest;
import com.recordandgrow.memory.dto.MemoryResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 记忆接口。三态流转只有用户能触发：AI 写 PENDING，用户 confirm / reject。
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public ApiResponse<PageResult<MemoryResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        MemoryType parsed = null;
        if (type != null && !type.isBlank()) {
            parsed = MemoryType.parse(type);
            if (parsed == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "type 只能是 PENDING / FACT / INFERRED");
            }
        }
        return ApiResponse.success(memoryService.list(parsed, page, size));
    }

    @GetMapping("/pending")
    public ApiResponse<List<MemoryResponse>> pending(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(memoryService.pending(limit));
    }

    @GetMapping("/search")
    public ApiResponse<List<MemoryResponse>> search(@RequestParam("q") String query,
                                                    @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.success(memoryService.search(query, limit));
    }

    @PostMapping
    public ApiResponse<MemoryResponse> create(@Valid @RequestBody MemoryCreateRequest request) {
        return ApiResponse.success(memoryService.createManual(request.content()));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<MemoryResponse> confirm(@PathVariable Long id) {
        return ApiResponse.success(memoryService.confirm(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id) {
        memoryService.reject(id);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        memoryService.delete(id);
        return ApiResponse.success();
    }
}
