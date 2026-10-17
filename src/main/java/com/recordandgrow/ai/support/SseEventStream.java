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

    @Override
    void close();
}
