package com.recordandgrow.ai;

/**
 * 流式对话句柄。
 *
 * <p>阻塞式迭代：调用方通常在一个虚拟线程里 {@code while (hasNext()) { next() }}。
 * {@link #close()} 用于取消上游请求；可重复调用。
 */
public interface ChatStream extends AutoCloseable {

    boolean hasNext();

    ChatChunk next();

    @Override
    void close();
}
