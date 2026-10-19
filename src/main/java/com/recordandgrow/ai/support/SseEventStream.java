package com.recordandgrow.ai.support;

import java.io.IOException;
import java.time.Duration;

/**
 * 已解析的 SSE 事件源。HTTP 实现基于 Flow.Publisher，测试可替换为内存实现。
 */
public interface SseEventStream extends AutoCloseable {

    /**
     * 读取一个完整事件的 data 载荷；超时返回 null。
     */
    String poll(Duration timeout) throws InterruptedException;

    boolean completed();

    boolean failed();

    IOException error();

    /**
     * 最近一次收到任意 SSE 行（含 keep-alive 注释）的时间，用于看门狗续命。
     */
    long lastActivityNanos();

    @Override
    void close();
}
