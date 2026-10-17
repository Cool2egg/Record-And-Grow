package com.recordandgrow.ai.deepseek;

import com.recordandgrow.ai.AiCallObserver;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatStream;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.support.SseEventStream;
import com.recordandgrow.ai.support.StreamWatchdog;

import java.io.IOException;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeepSeek SSE 流实现。
 *
 * <p>不阻塞读 socket：事件由 {@link SseEventStream} 的队列提供，消费方带超时轮询。
 * 看门狗在另一个调度线程上检查 idle / first-token / total，并在超时时关闭事件源。
 */
final class JdkChatStream implements ChatStream {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private final SseEventStream source;
    private final DeepSeekSseParser parser;
    private final AiCallObserver observer;
    private final String clientName;
    private final long startedAt = System.nanoTime();
    private final AtomicReference<AiException> failure = new AtomicReference<>();
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final StreamWatchdog watchdog;

    /** 只在 close() 置位；与 ended（observer 通知）分开，避免取消/失败/正常结束混在一起。 */
    private volatile boolean cancelled;
    /** 是否收到协议终止符 data: [DONE]。 */
    private boolean sawDone;
    private volatile boolean finished;
    private ChatChunk pending;
    private ChatUsage lastUsage;

    JdkChatStream(SseEventStream source,
                  DeepSeekSseParser parser,
                  AiCallObserver observer,
                  String clientName,
                  Duration idleTimeout,
                  Duration firstTokenTimeout,
                  Duration totalTimeout) {
        this.source = source;
        this.parser = parser;
        this.observer = observer;
        this.clientName = clientName;
        this.watchdog = new StreamWatchdog(idleTimeout, firstTokenTimeout, totalTimeout, this::onWatchdogTimeout);
    }

    @Override
    public boolean hasNext() {
        if (cancelled) {
            return false;
        }
        if (pending != null) {
            return true;
        }
        AiException existing = failure.get();
        if (existing != null) {
            throw existing;
        }
        while (true) {
            if (cancelled || finished) {
                return false;
            }
            String data;
            try {
                data = source.poll(POLL_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw fail(new AiException(AiErrorType.NETWORK, "等待流式响应被中断", e));
            }
            // 任意 SSE 行（含 keep-alive 注释）都续命；注释不会变成 data，所以不能只在 data 分支里调。
            watchdog.activity(source.lastActivityNanos());
            if (cancelled) {
                return false;
            }
            if (data != null) {
                if ("[DONE]".equals(data)) {
                    sawDone = true;
                    finishNormally();
                    return false;
                }
                ChatChunk chunk;
                try {
                    chunk = parser.parse(data);
                } catch (AiException e) {
                    throw fail(e);
                }
                if (chunk != null) {
                    if (chunk.usage() != null) {
                        lastUsage = chunk.usage();
                    }
                    if (chunk.hasContent()) {
                        watchdog.firstContent();
                    }
                    pending = chunk;
                    return true;
                }
                continue;
            }
            if (watchdog.timeoutFired()) {
                throw fail(new AiException(AiErrorType.TIMEOUT, "DeepSeek 流式响应超时"));
            }
            if (source.failed()) {
                throw fail(new AiException(AiErrorType.STREAM_INTERRUPTED,
                        "DeepSeek 流式响应中断", source.error()));
            }
            if (source.completed()) {
                if (sawDone) {
                    finishNormally();
                    return false;
                }
                // 干净 EOF 但没收到 [DONE]：上游/代理提前关连接，不能当成正常结束。
                throw fail(new AiException(AiErrorType.STREAM_INTERRUPTED,
                        "DeepSeek 流在 [DONE] 前结束"));
            }
        }
    }

    @Override
    public ChatChunk next() {
        if (cancelled) {
            throw new NoSuchElementException("流已取消");
        }
        if (!hasNext()) {
            throw new NoSuchElementException("流已结束");
        }
        ChatChunk chunk = pending;
        pending = null;
        return chunk;
    }

    @Override
    public void close() {
        cancelled = true;
        boolean shouldNotify = ended.compareAndSet(false, true);
        closeResources();
        if (shouldNotify) {
            observer.onStreamEnd(clientName, usageOrEmpty(), elapsed(), true);
        }
    }

    private void onWatchdogTimeout() {
        closeResources();
    }

    private void finishNormally() {
        finished = true;
        closeResources();
        if (ended.compareAndSet(false, true)) {
            observer.onStreamEnd(clientName, usageOrEmpty(), elapsed(), false);
        }
    }

    private AiException fail(AiException exception) {
        failure.compareAndSet(null, exception);
        closeResources();
        if (ended.compareAndSet(false, true)) {
            observer.onChatError(clientName, exception.getType(), elapsed(), 1);
        }
        return exception;
    }

    private void closeResources() {
        watchdog.close();
        source.close();
    }

    private ChatUsage usageOrEmpty() {
        return lastUsage == null ? ChatUsage.empty() : lastUsage;
    }

    private Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
