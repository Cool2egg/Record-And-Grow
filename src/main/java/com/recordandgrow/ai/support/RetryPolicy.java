package com.recordandgrow.ai.support;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试策略：指数退避 + 抖动，优先使用上游的 Retry-After。
 */
public class RetryPolicy {

    /**
     * 单客户端调用（如 embedding）的尝试上限。主备降级时每个 delegate 自己带 maxAttempts，
     * 这里的值只用于 backoff/pause。
     */
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration maxRetryAfter;

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Duration maxRetryAfter) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxRetryAfter = maxRetryAfter;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean canRetry(AiException e, int attempt) {
        return attempt < maxAttempts && e.retryable();
    }

    /**
     * @param attempt 已失败的尝试次数（1 表示第一次失败后）
     */
    public Duration backoff(AiException e, int attempt) {
        if (e.getRetryAfter() != null) {
            return min(e.getRetryAfter(), maxRetryAfter);
        }
        long base = initialBackoff.toMillis() * (1L << Math.max(0, attempt - 1));
        long capped = Math.min(base, maxBackoff.toMillis());
        long jitter = (long) (capped * 0.2 * ThreadLocalRandom.current().nextDouble(-1, 1));
        return Duration.ofMillis(Math.max(0, capped + jitter));
    }

    public void pause(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiErrorType.NETWORK, "重试等待被中断", e);
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
