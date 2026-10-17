package com.recordandgrow.controller;

import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.dto.AiChatRequest;
import com.recordandgrow.ai.dto.AiChatResponse;
import com.recordandgrow.ai.dto.AiEmbeddingRequest;
import com.recordandgrow.ai.dto.AiEmbeddingResponse;
import com.recordandgrow.ai.sse.ChatSseService;
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

import java.util.List;

/**
 * AI 自检接口：用于模块三验证 chat / SSE / embedding，模块四会替换成业务接口。
 */
@RestController
@RequestMapping("/api/ai")
public class AiDiagnosticController {

    private final ChatClient chatClient;
    private final EmbeddingClient embeddingClient;
    private final ChatSseService chatSseService;

    public AiDiagnosticController(ChatClient chatClient,
                                  EmbeddingClient embeddingClient,
                                  ChatSseService chatSseService) {
        this.chatClient = chatClient;
        this.embeddingClient = embeddingClient;
        this.chatSseService = chatSseService;
    }

    @PostMapping("/chat")
    public ApiResponse<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {
        return ApiResponse.success(AiChatResponse.from(chatClient.complete(request.toDomain())));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public ResponseEntity<SseEmitter> chatStream(@Valid @RequestBody AiChatRequest request,
                                                 HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8"))
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .body(chatSseService.stream(request.toDomain()));
    }

    @PostMapping("/embedding")
    public ApiResponse<AiEmbeddingResponse> embedding(@Valid @RequestBody AiEmbeddingRequest request) {
        List<float[]> vectors = embeddingClient.embed(request.texts());
        return ApiResponse.success(new AiEmbeddingResponse(vectors.size(), embeddingClient.dimensions()));
    }
}
