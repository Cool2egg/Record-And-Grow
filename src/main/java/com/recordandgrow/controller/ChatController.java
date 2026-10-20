package com.recordandgrow.controller;

import com.recordandgrow.agent.ChatService;
import com.recordandgrow.agent.dto.ChatAnswerResponse;
import com.recordandgrow.agent.dto.ChatAskRequest;
import com.recordandgrow.infra.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 业务对话接口。
 *
 * <p>流式与非流式共用同一次「检索 + 组装」，区别只在最后是转发 SSE 还是一次性返回。
 * 参数错误、无 key、DB 故障都在建立 SSE 之前抛出，由 {@code GlobalExceptionHandler} 统一处理。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ApiResponse<ChatAnswerResponse> chat(@Valid @RequestBody ChatAskRequest request) {
        return ApiResponse.success(chatService.ask(request));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public ResponseEntity<SseEmitter> stream(@Valid @RequestBody ChatAskRequest request,
                                             HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8"))
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .body(chatService.stream(request));
    }
}
