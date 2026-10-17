package com.recordandgrow.controller;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatStream;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.FinishReason;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.ai.sse.ChatSseService;
import com.recordandgrow.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiDiagnosticControllerTest {

    private FakeChatClient chatClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatClient = new FakeChatClient();
        AiProperties properties = new AiProperties();
        ChatSseService sseService = new ChatSseService(chatClient, properties);
        AiDiagnosticController controller = new AiDiagnosticController(chatClient, new FakeEmbeddingClient(), sseService);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void chatReturnsEnvelope() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").value("ok"))
                .andExpect(jsonPath("$.data.finishReason").value("STOP"));
    }

    @Test
    void chatMapsAiExceptionTo503() throws Exception {
        chatClient.completeError = new AiException(AiErrorType.AUTHENTICATION, "bad key");

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50301))
                .andExpect(jsonPath("$.message").value("AI 服务暂时不可用"));
    }

    @Test
    void embeddingReturnsCountAndDimensions() throws Exception {
        mockMvc.perform(post("/api/ai/embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texts\":[\"a\",\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.dimensions").value(4));
    }

    @Test
    void rejectsEmptyMessages() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void rejectsUnknownRole() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"robot\",\"content\":\"hi\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("role")));
    }

    private static final class FakeChatClient implements ChatClient {

        private AiException completeError;

        @Override
        public ChatResponse complete(ChatRequest request) {
            if (completeError != null) {
                throw completeError;
            }
            return new ChatResponse("ok", FinishReason.STOP, new ChatUsage(1, 1, 2));
        }

        @Override
        public ChatStream stream(ChatRequest request) {
            return new ChatStream() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public ChatChunk next() {
                    throw new java.util.NoSuchElementException();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class FakeEmbeddingClient implements EmbeddingClient {
        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(text -> new float[4]).toList();
        }

        @Override
        public int dimensions() {
            return 4;
        }
    }
}
