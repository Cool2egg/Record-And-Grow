package com.recordandgrow.ai.support;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherSseEventStreamTest {

    @Test
    void parsesEventsAndIgnoresComments() throws Exception {
        PublisherSseEventStream source = source();
        source.onNext(List.of(bytes(": keep-alive\ndata: {\"a\":1}\n\ndata: [DONE]\n\n")));
        source.onComplete();

        assertThat(source.poll(Duration.ofSeconds(1))).isEqualTo("{\"a\":1}");
        assertThat(source.poll(Duration.ofSeconds(1))).isEqualTo("[DONE]");
        assertThat(source.completed()).isTrue();
    }

    @Test
    void joinsMultiLineData() throws Exception {
        PublisherSseEventStream source = source();
        source.onNext(List.of(bytes("data: line1\ndata: line2\n\n")));
        source.onComplete();

        assertThat(source.poll(Duration.ofSeconds(1))).isEqualTo("line1\nline2");
    }

    @Test
    void handlesUtf8SplitAcrossBuffers() throws Exception {
        byte[] payload = "data: 你好\n\n".getBytes(StandardCharsets.UTF_8);
        PublisherSseEventStream source = source();
        // 在“你”的 UTF-8 三字节中间切开
        source.onNext(List.of(
                ByteBuffer.wrap(payload, 0, 8),
                ByteBuffer.wrap(payload, 8, payload.length - 8)));
        source.onComplete();

        assertThat(source.poll(Duration.ofSeconds(1))).isEqualTo("你好");
    }

    @Test
    void onErrorMarksFailed() {
        PublisherSseEventStream source = source();
        source.onError(new IllegalStateException("reset"));

        assertThat(source.failed()).isTrue();
        assertThat(source.completed()).isTrue();
        assertThat(source.error()).hasMessageContaining("reset");
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
