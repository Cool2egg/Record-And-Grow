package com.recordandgrow.ai.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK HttpClient {@code BodyHandlers.ofPublisher()} 的 SSE 事件源。
 *
 * <p>不使用“阻塞读 + 关闭 InputStream”来取消：关闭流不保证唤醒阻塞读。
 * 这里由 HttpClient 线程解析字节流并写入有界队列，消费方用带超时的 poll，
 * 取消时直接 {@code subscription.cancel()}，不依赖任何线程被唤醒。
 */
public final class PublisherSseEventStream implements SseEventStream, Flow.Subscriber<List<ByteBuffer>> {

    private static final int MAX_EVENTS = 4096;

    private final BlockingQueue<String> events = new LinkedBlockingQueue<>(MAX_EVENTS);
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();

    private volatile Flow.Subscription subscription;
    private volatile boolean closed;
    private volatile boolean completed;
    private volatile IOException error;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (closed) {
            subscription.cancel();
        } else {
            subscription.request(Long.MAX_VALUE);
        }
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (closed) {
            return;
        }
        for (ByteBuffer buffer : buffers) {
            while (buffer.hasRemaining()) {
                byte value = buffer.get();
                if (value == '\n') {
                    String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
                    line.reset();
                    if (text.endsWith("\r")) {
                        text = text.substring(0, text.length() - 1);
                    }
                    processLine(text);
                } else {
                    line.write(value);
                }
            }
        }
    }

    @Override
    public void onError(Throwable throwable) {
        error = new IOException(throwable == null ? "SSE 流异常" : throwable.getMessage(), throwable);
        completed = true;
    }

    @Override
    public void onComplete() {
        if (closed) {
            return;
        }
        if (line.size() > 0) {
            processLine(new String(line.toByteArray(), StandardCharsets.UTF_8));
            line.reset();
        }
        flushEvent();
        completed = true;
    }

    @Override
    public String poll(Duration timeout) throws InterruptedException {
        return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean completed() {
        return completed;
    }

    @Override
    public boolean failed() {
        return error != null;
    }

    @Override
    public IOException error() {
        return error;
    }

    @Override
    public void close() {
        closed = true;
        Flow.Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
    }

    private void processLine(String text) {
        if (text.isEmpty()) {
            flushEvent();
            return;
        }
        if (text.charAt(0) == ':') {
            return;
        }
        if (text.startsWith("data:")) {
            String value = text.substring(5);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value);
        }
    }

    private void flushEvent() {
        if (data.length() == 0 || closed) {
            return;
        }
        String event = data.toString();
        data.setLength(0);
        try {
            events.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = new IOException("SSE 事件队列被中断", e);
            completed = true;
            close();
        }
    }
}
