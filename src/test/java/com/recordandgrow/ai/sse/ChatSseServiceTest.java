package com.recordandgrow.ai.sse;

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
import com.recordandgrow.controller.AiDiagnosticController;
import com.recordandgrow.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatSseServiceTest {

    private FakeChatClient chatClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatClient = new FakeChatClient();
        ChatSseService service = new ChatSseService(chatClient, Duration.ofSeconds(10), Duration.ofMillis(50));
        AiDiagnosticController controller = new AiDiagnosticController(chatClient, new FakeEmbeddingClient(), service);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void streamsStartDeltaDone() throws Exception {
        chatClient.stream = new FakeStream(List.of(
                new ChatChunk("你", null, null),
                new ChatChunk("好", FinishReason.STOP, new ChatUsage(1, 2, 3))), 0);

        MvcResult result = mockMvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("你")))
                .andExpect(content().string(containsString("好")))
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("STOP")));
    }

    @Test
    void sendsErrorEventWhenUpstreamFails() throws Exception {
        chatClient.streamError = new AiException(AiErrorType.RATE_LIMITED, "limited");

        MvcResult result = mockMvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(containsString("RATE_LIMITED")))
                .andExpect(content().string(containsString("50301")));
    }

    @Test
    void sendsHeartbeatWhileWaitingForFirstChunk() throws Exception {
        chatClient.stream = new FakeStream(List.of(
                new ChatChunk("a", FinishReason.STOP, null)), 300);

        MvcResult result = mockMvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ping")));
    }

    private static String body() {
        return "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static final class FakeChatClient implements ChatClient {

        private ChatStream stream;
        private AiException streamError;

        @Override
        public ChatResponse complete(ChatRequest request) {
            return new ChatResponse("ok", FinishReason.STOP, new ChatUsage(1, 1, 2));
        }

        @Override
        public ChatStream stream(ChatRequest request) {
            if (streamError != null) {
                throw streamError;
            }
            return stream;
        }
    }

    private static final class FakeEmbeddingClient implements EmbeddingClient {
        @Override
        public List<float[]> embed(List<String> texts) {
            return List.of(new float[4]);
        }

        @Override
        public int dimensions() {
            return 4;
        }
    }

    private static final class FakeStream implements ChatStream {

        private final Deque<ChatChunk> chunks = new ArrayDeque<>();
        private final long delayMillis;

        private FakeStream(List<ChatChunk> chunks, long delayMillis) {
            this.chunks.addAll(chunks);
            this.delayMillis = delayMillis;
        }

        @Override
        public boolean hasNext() {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !chunks.isEmpty();
        }

        @Override
        public ChatChunk next() {
            return chunks.poll();
        }

        @Override
        public void close() {
        }
    }
}
