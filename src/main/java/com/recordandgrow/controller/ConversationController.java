package com.recordandgrow.controller;

import com.recordandgrow.infra.response.ApiResponse;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.memory.ConversationService;
import com.recordandgrow.memory.dto.ConversationCreateRequest;
import com.recordandgrow.memory.dto.ConversationResponse;
import com.recordandgrow.memory.dto.MessageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话接口。聊天接口传 conversationId 时复用这里的会话；不传则每轮新建。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ApiResponse<PageResult<ConversationResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(conversationService.list(page, size));
    }

    @PostMapping
    public ApiResponse<ConversationResponse> create(
            @Valid @RequestBody(required = false) ConversationCreateRequest request) {
        return ApiResponse.success(conversationService.create(request == null ? null : request.title()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationResponse> get(@PathVariable Long id) {
        return ApiResponse.success(conversationService.get(id));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<PageResult<MessageResponse>> messages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(conversationService.messages(id, page, size));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return ApiResponse.success();
    }
}
