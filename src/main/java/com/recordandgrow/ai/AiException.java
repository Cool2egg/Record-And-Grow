package com.recordandgrow.ai;

import java.time.Duration;

/**
 * AI 调用异常：业务层唯一需要认识的 AI 异常类型。
 *
 * <p>不携带上游原始响应体，避免把供应商细节和可能的敏感内容透传到业务层/客户端。
 * 原始错误只进日志，并且调用方负责截断与脱敏。
 */
public class AiException extends RuntimeException {

    private final AiErrorType type;
    private final String provider;
    private final Integer statusCode;
    private final Duration retryAfter;

    public AiException(AiErrorType type, String message) {
        this(type, message, null, null, null, null);
    }

    public AiException(AiErrorType type, String message, Throwable cause) {
        this(type, message, null, null, null, cause);
    }

    public AiException(AiErrorType type, String message, String provider,
                       Integer statusCode, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.provider = provider;
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public AiErrorType getType() {
        return type;
    }

    public String getProvider() {
        return provider;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public boolean retryable() {
        return type.retryable();
    }
}
