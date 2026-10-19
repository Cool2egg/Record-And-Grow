package com.recordandgrow.ai;

/**
 * AI 调用失败的分类。
 *
 * <p>{@code retryable} 只表示“同一请求再试一次可能成功”，不表示业务层应该重试；
 * 重试由 {@code FailoverChatClient} / {@code QwenEmbeddingClient} 内部的策略统一处理。
 */
public enum AiErrorType {

    /** 没有配置 API key / base URL。 */
    NOT_CONFIGURED(false),
    /** 上游返回 400 / 422，通常是请求构造问题。 */
    INVALID_REQUEST(false),
    /** 上游返回 401。 */
    AUTHENTICATION(false),
    /** 上游返回 402。 */
    INSUFFICIENT_BALANCE(false),
    /** 上游返回 429。 */
    RATE_LIMITED(true),
    /** 上游返回 5xx。 */
    UPSTREAM_ERROR(true),
    /** 连接、首包、空闲或总时长超时。 */
    TIMEOUT(true),
    /** DNS / 连接重置 / 读写 IO 异常。 */
    NETWORK(true),
    /** 已建立流后中断；此时不能再重试，否则会重复输出。 */
    STREAM_INTERRUPTED(false),
    /** 上游 2xx 但响应格式不符合预期。 */
    BAD_RESPONSE(false);

    private final boolean retryable;

    AiErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
