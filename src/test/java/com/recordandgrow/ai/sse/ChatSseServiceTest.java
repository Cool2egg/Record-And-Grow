package com.recordandgrow.ai.sse;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void senderRejectsEventsAfterCancel() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ChatSseService.SseSender sender = new ChatSseService.SseSender(emitter, cancelled);

        assertThat(sender.send(SseEmitter.event().name("delta").data(new SseEvents.Delta("a")))).isTrue();
        sender.cancel();
        assertThat(sender.send(SseEmitter.event().name("delta").data(new SseEvents.Delta("b")))).isFalse();
        assertThat(emitter.sends).isEqualTo(1);
    }

    @Test
    void unexpectedRuntimeExceptionSendsError() throws Exception {
        chatClient.streamRuntimeError = new IllegalStateException("boom");
        RecordingEmitter emitter = new RecordingEmitter();
        ChatSseService service = new ChatSseService(
                chatClient, Duration.ofSeconds(5), Duration.ofSeconds(5), () -> emitter);

        service.stream(chatRequest());

        assertThat(emitter.awaitCompletion()).isTrue();
        assertThat(emitter.payloads).anyMatch(payload -> payload.contains("UPSTREAM_ERROR"));
    }

    @Test
    void clientDisconnectDoesNotSendError() throws Exception {
        chatClient.stream = new FakeStream(List.of(new ChatChunk("a", null, null)), 0);
        RecordingEmitter emitter = new RecordingEmitter();
        emitter.failOnSend = 2; // start 成功，delta 写入失败
        ChatSseService service = new ChatSseService(
                chatClient, Duration.ofSeconds(5), Duration.ofSeconds(5), () -> emitter);

        service.stream(chatRequest());

        assertThat(emitter.awaitCompletion()).isTrue();
        assertThat(emitter.payloads).noneMatch(payload -> payload.contains("error"));
    }

    @Test
    void sendsSourcesBetweenStartAndDelta() throws Exception {
        chatClient.stream = new FakeStream(List.of(
                new ChatChunk("a", FinishReason.STOP, null)), 0);
        RecordingEmitter emitter = new RecordingEmitter();
        ChatSseService service = new ChatSseService(
                chatClient, Duration.ofSeconds(5), Duration.ofMillis(500), () -> emitter);

        SseEvents.Sources sources = new SseEvents.Sources(
                List.of(new SseEvents.SourceItem("T1", "timeline", 1L,
                        OffsetDateTime.parse("2026-10-18T20:00:00+08:00"), null, "excerpt")),
                new SseEvents.RetrievalMeta(
                        OffsetDateTime.parse("2026-10-12T00:00:00+08:00"),
                        OffsetDateTime.parse("2026-10-19T00:00:00+08:00"),
                        "RELATIVE_WEEK", false, 1, 1, 1, false, false,
                        List.of(), false));

        service.stream(chatRequest(), "request-1", sources);

        assertThat(emitter.awaitCompletion()).isTrue();
        assertThat(emitter.payloads).containsSubsequence(
                "event:start\ndata:",
                "event:sources\ndata:",
                "event:delta\ndata:",
                "event:done\ndata:");
    }

    private static ChatRequest chatRequest() {
        return new ChatRequest(List.of(new ChatMessage(ChatMessage.Role.USER, "hi")), null, null);
    }

    private static String body() {
        return "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static final class FakeChatClient implements ChatClient {

        private ChatStream stream;
        private AiException streamError;
        private RuntimeException streamRuntimeError;

        @Override
        public ChatResponse complete(ChatRequest request) {
            return new ChatResponse("ok", FinishReason.STOP, new ChatUsage(1, 1, 2));
        }

        @Override
        public ChatStream stream(ChatRequest request) {
            if (streamRuntimeError != null) {
                throw streamRuntimeError;
            }
            if (streamError != null) {
                throw streamError;
            }
            return stream;
        }
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> payloads = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch completed = new CountDownLatch(1);
        private int sends;
        private int failOnSend;

        @Override
        public synchronized void send(SseEmitter.SseEventBuilder builder) throws IOException {
            sends++;
            if (failOnSend > 0 && sends == failOnSend) {
                throw new IOException("client gone");
            }
            builder.build().forEach(data -> payloads.add(String.valueOf(data.getData())));
        }

        @Override
        public synchronized void complete() {
            completed.countDown();
            super.complete();
        }

        private boolean awaitCompletion() throws InterruptedException {
            return completed.await(2, TimeUnit.SECONDS);
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
