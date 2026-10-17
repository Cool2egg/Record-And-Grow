package com.recordandgrow.ai.support;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 测试用 SSE 事件源：可手动 emit / complete / fail，不依赖网络。
 */
public final class FakeSseEventStream implements SseEventStream {

    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    private volatile boolean completed;
    private volatile boolean failed;
    private volatile IOException error;
    private volatile long lastActivityNanos = System.nanoTime();

    public void emit(String data) {
        lastActivityNanos = System.nanoTime();
        events.add(data);
    }

    public void complete() {
        completed = true;
    }

    public void fail(IOException exception) {
        this.error = exception;
        this.failed = true;
        this.completed = true;
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
        return failed;
    }

    @Override
    public IOException error() {
        return error;
    }

    @Override
    public long lastActivityNanos() {
        return lastActivityNanos;
    }

    @Override
    public void close() {
        completed = true;
    }
}
