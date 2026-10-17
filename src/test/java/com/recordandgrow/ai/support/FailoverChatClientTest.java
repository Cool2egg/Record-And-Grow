package com.recordandgrow.ai.support;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatStream;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.FinishReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverChatClientTest {

    private final ChatRequest request = new ChatRequest(
            List.of(new com.recordandgrow.ai.ChatMessage(
                    com.recordandgrow.ai.ChatMessage.Role.USER, "hi")), 64, null);

    private final RetryPolicy policy = new RetryPolicy(2, Duration.ZERO, Duration.ZERO, Duration.ZERO);

    @Test
    void retriesPrimaryThenUsesFallback() {
        FakeChatClient primary = new FakeChatClient(
                error(AiErrorType.UPSTREAM_ERROR), error(AiErrorType.UPSTREAM_ERROR));
        FakeChatClient fallback = new FakeChatClient(response("fallback"));

        ChatResponse result = failover(primary, fallback).complete(request);

        assertThat(result.content()).isEqualTo("fallback");
        assertThat(primary.calls).isEqualTo(2);
        assertThat(fallback.calls).isEqualTo(1);
    }

    @Test
    void nonRetryableDoesNotFallback() {
        FakeChatClient primary = new FakeChatClient(error(AiErrorType.AUTHENTICATION));
        FakeChatClient fallback = new FakeChatClient(response("fallback"));

        assertThatThrownBy(() -> failover(primary, fallback).complete(request))
                .isInstanceOfSatisfying(AiException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiErrorType.AUTHENTICATION));
        assertThat(primary.calls).isEqualTo(1);
        assertThat(fallback.calls).isEqualTo(0);
    }

    @Test
    void primarySuccessOnRetryDoesNotFallback() {
        FakeChatClient primary = new FakeChatClient(
                error(AiErrorType.RATE_LIMITED), response("primary"));
        FakeChatClient fallback = new FakeChatClient(response("fallback"));

        ChatResponse result = failover(primary, fallback).complete(request);

        assertThat(result.content()).isEqualTo("primary");
        assertThat(primary.calls).isEqualTo(2);
        assertThat(fallback.calls).isEqualTo(0);
    }

    @Test
    void streamConnectionFailureFallsBack() {
        FakeChatClient primary = new FakeChatClient(
                error(AiErrorType.UPSTREAM_ERROR), error(AiErrorType.UPSTREAM_ERROR));
        FakeChatClient fallback = new FakeChatClient(new FakeStream());

        ChatStream stream = failover(primary, fallback).stream(request);

        assertThat(stream).isNotNull();
        assertThat(primary.calls).isEqualTo(2);
        assertThat(fallback.calls).isEqualTo(1);
    }

    private FailoverChatClient failover(FakeChatClient primary, FakeChatClient fallback) {
        return new FailoverChatClient(List.of(
                new FailoverChatClient.Delegate("primary", primary, 2),
                new FailoverChatClient.Delegate("fallback", fallback, 1)
        ), policy);
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(content, FinishReason.STOP, new ChatUsage(1, 1, 2));
    }

    private static AiException error(AiErrorType type) {
        return new AiException(type, "test");
    }

    private static final class FakeChatClient implements ChatClient {

        private final Deque<Object> outcomes;
        private int calls;

        private FakeChatClient(Object... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            calls++;
            Object outcome = outcomes.isEmpty() ? new IllegalStateException("no outcome") : outcomes.poll();
            if (outcome instanceof AiException error) {
                throw error;
            }
            if (outcome instanceof ChatResponse response) {
                return response;
            }
            throw new IllegalStateException("unexpected outcome: " + outcome);
        }

        @Override
        public ChatStream stream(ChatRequest request) {
            calls++;
            Object outcome = outcomes.isEmpty() ? new IllegalStateException("no outcome") : outcomes.poll();
            if (outcome instanceof AiException error) {
                throw error;
            }
            if (outcome instanceof ChatStream stream) {
                return stream;
            }
            throw new IllegalStateException("unexpected outcome: " + outcome);
        }
    }

    private static final class FakeStream implements ChatStream {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public com.recordandgrow.ai.ChatChunk next() {
            throw new java.util.NoSuchElementException();
        }

        @Override
        public void close() {
        }
    }
}
