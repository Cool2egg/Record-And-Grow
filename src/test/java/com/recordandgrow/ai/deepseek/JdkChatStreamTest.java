package com.recordandgrow.ai.deepseek;

import com.recordandgrow.ai.AiCallObserver;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.NoopAiCallObserver;
import com.recordandgrow.ai.support.PublisherSseEventStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class JdkChatStreamTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsContentUntilDone() {
        String body = """
                : keep-alive

                data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                data: [DONE]

                """;
        List<String> contents = new ArrayList<>();
        ChatUsage[] usage = new ChatUsage[1];
        try (JdkChatStream stream = stream(body, new NoopAiCallObserver(), 5, 5)) {
            while (stream.hasNext()) {
                ChatChunk chunk = stream.next();
                if (chunk.hasContent()) {
                    contents.add(chunk.contentDelta());
                }
                if (chunk.usage() != null) {
                    usage[0] = chunk.usage();
                }
            }
        }
        assertThat(String.join("", contents)).isEqualTo("你好");
        assertThat(usage[0].totalTokens()).isEqualTo(3);
    }

    @Test
    void midStreamFailureBecomesStreamInterrupted() {
        PublisherSseEventStream source = source();
        source.onNext(List.of(bytes("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n")));
        source.onError(new IOException("connection reset"));

        try (JdkChatStream stream = stream(source, new NoopAiCallObserver(), 5, 5)) {
            assertThat(stream.hasNext()).isTrue();
            assertThat(stream.next().contentDelta()).isEqualTo("a");
            assertThatThrownBy(stream::hasNext)
                    .isInstanceOfSatisfying(AiException.class,
                            e -> assertThat(e.getType()).isEqualTo(AiErrorType.STREAM_INTERRUPTED));
        }
    }

    @Test
    void idleTimeoutFires() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            PublisherSseEventStream source = source();
            try (JdkChatStream stream = stream(source, new NoopAiCallObserver(), 0.15, 5)) {
                assertThatThrownBy(stream::hasNext)
                        .isInstanceOfSatisfying(AiException.class,
                                e -> assertThat(e.getType()).isEqualTo(AiErrorType.TIMEOUT));
            }
        });
    }

    @Test
    void closeIsIdempotentAndReportsCancellation() {
        AtomicInteger cancelled = new AtomicInteger();
        AiCallObserver observer = new AiCallObserver() {
            @Override
            public void onStreamEnd(String client, ChatUsage usage, Duration latency, boolean wasCancelled) {
                if (wasCancelled) {
                    cancelled.incrementAndGet();
                }
            }
        };
        PublisherSseEventStream source = source();
        source.onNext(List.of(bytes("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n")));
        JdkChatStream stream = new JdkChatStream(source, new DeepSeekSseParser(mapper), observer, "test",
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        stream.close();
        stream.close();
        assertThat(cancelled.get()).isEqualTo(1);
    }

    private JdkChatStream stream(String body, AiCallObserver observer, double idleSeconds, double totalSeconds) {
        PublisherSseEventStream source = source();
        source.onNext(List.of(bytes(body)));
        source.onComplete();
        return stream(source, observer, idleSeconds, totalSeconds);
    }

    private JdkChatStream stream(PublisherSseEventStream source, AiCallObserver observer,
                                 double idleSeconds, double totalSeconds) {
        return new JdkChatStream(source, new DeepSeekSseParser(mapper), observer, "test",
                Duration.ofMillis((long) (idleSeconds * 1000)),
                Duration.ofSeconds((long) totalSeconds),
                Duration.ofSeconds((long) totalSeconds));
    }

    private static PublisherSseEventStream source() {
        PublisherSseEventStream source = new PublisherSseEventStream();
        source.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
            }
        });
        return source;
    }

    private static ByteBuffer bytes(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }
}
