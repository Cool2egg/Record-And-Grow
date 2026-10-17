package com.recordandgrow.ai.support;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 流式请求看门狗：检查空闲、首包、总时长三个 deadline。
 *
 * <p>触发时调用 {@code onTimeout}，由流实现关闭底层 InputStream，让阻塞读抛异常退出。
 * 共享一个 daemon 调度线程，不为每条流创建线程。
 */
public final class StreamWatchdog implements AutoCloseable {

    private static final Duration DEFAULT_TICK = Duration.ofMillis(200);

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ai-stream-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private final long startedAt;
    private final long idleNanos;
    private final long firstTokenNanos;
    private final long totalNanos;
    private final Runnable onTimeout;
    private final ScheduledFuture<?> future;

    private volatile long lastActivity;
    private volatile long firstContent;
    private volatile boolean stopped;
    private volatile boolean timeoutFired;

    public StreamWatchdog(Duration idleTimeout, Duration firstTokenTimeout, Duration totalTimeout,
                          Runnable onTimeout) {
        this(idleTimeout, firstTokenTimeout, totalTimeout, onTimeout, DEFAULT_TICK);
    }

    public StreamWatchdog(Duration idleTimeout, Duration firstTokenTimeout, Duration totalTimeout,
                          Runnable onTimeout, Duration tick) {
        this.startedAt = System.nanoTime();
        this.lastActivity = startedAt;
        this.idleNanos = idleTimeout.toNanos();
        this.firstTokenNanos = firstTokenTimeout.toNanos();
        this.totalNanos = totalTimeout.toNanos();
        this.onTimeout = onTimeout;
        long tickMillis = Math.max(10, tick.toMillis());
        this.future = SCHEDULER.scheduleAtFixedRate(this::check, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    /** 收到任意一行（包括 keep-alive 注释）时调用。 */
    public void activity() {
        lastActivity = System.nanoTime();
    }

    /** 收到第一段正文时调用。 */
    public void firstContent() {
        if (firstContent == 0) {
            firstContent = System.nanoTime();
        }
    }

    public boolean timeoutFired() {
        return timeoutFired;
    }

    private void check() {
        if (stopped || timeoutFired) {
            return;
        }
        long now = System.nanoTime();
        boolean idle = now - lastActivity > idleNanos;
        boolean noFirstContent = firstContent == 0 && now - startedAt > firstTokenNanos;
        boolean total = now - startedAt > totalNanos;
        if (idle || noFirstContent || total) {
            timeoutFired = true;
            stopped = true;
            try {
                onTimeout.run();
            } finally {
                future.cancel(false);
            }
        }
    }

    @Override
    public void close() {
        stopped = true;
        future.cancel(false);
    }
}
