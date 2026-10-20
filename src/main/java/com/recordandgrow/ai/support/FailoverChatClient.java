package com.recordandgrow.ai.support;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 主备降级：按顺序尝试，主用可重试，备用只尝试一次。
 *
 * <p>只对连接阶段的失败降级；{@link ChatClient#stream} 返回后不再降级，
 * 避免已经输出给客户端的内容重复。
 */
public final class FailoverChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatClient.class);

    public record Delegate(String name, ChatClient client, int maxAttempts) {
    }

    private final List<Delegate> delegates;
    private final RetryPolicy retryPolicy;

    public FailoverChatClient(List<Delegate> delegates, RetryPolicy retryPolicy) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个 ChatClient");
        }
        this.delegates = List.copyOf(delegates);
        this.retryPolicy = retryPolicy;
    }

    @Override
    public boolean isConfigured() {
        return delegates.stream().anyMatch(delegate -> delegate.client().isConfigured());
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        AiException last = null;
        for (Delegate delegate : delegates) {
            if (!delegate.client().isConfigured()) {
                log.debug("跳过未配置的 AI 客户端: client={}", delegate.name());
                continue;
            }
            for (int attempt = 1; attempt <= delegate.maxAttempts(); attempt++) {
                try {
                    return delegate.client().complete(request);
                } catch (AiException e) {
                    last = e;
                    if (!e.retryable()) {
                        throw e;
                    }
                    if (attempt < delegate.maxAttempts() && !Thread.currentThread().isInterrupted()) {
                        log.warn("AI 调用失败，准备重试: client={}, type={}, attempt={}",
                                delegate.name(), e.getType(), attempt);
                        retryPolicy.pause(retryPolicy.backoff(e, attempt));
                    } else {
                        log.warn("AI 调用失败，尝试降级: client={}, type={}", delegate.name(), e.getType());
                    }
                }
            }
        }
        throw last == null
                ? new AiException(AiErrorType.UPSTREAM_ERROR, "没有可用的 AI 客户端")
                : last;
    }

    @Override
    public ChatStream stream(ChatRequest request) {
        AiException last = null;
        for (Delegate delegate : delegates) {
            if (!delegate.client().isConfigured()) {
                log.debug("跳过未配置的 AI 客户端: client={}", delegate.name());
                continue;
            }
            for (int attempt = 1; attempt <= delegate.maxAttempts(); attempt++) {
                try {
                    return delegate.client().stream(request);
                } catch (AiException e) {
                    last = e;
                    if (!e.retryable()) {
                        throw e;
                    }
                    if (attempt < delegate.maxAttempts() && !Thread.currentThread().isInterrupted()) {
                        log.warn("AI 流式连接失败，准备重试: client={}, type={}, attempt={}",
                                delegate.name(), e.getType(), attempt);
                        retryPolicy.pause(retryPolicy.backoff(e, attempt));
                    } else {
                        log.warn("AI 流式连接失败，尝试降级: client={}, type={}", delegate.name(), e.getType());
                    }
                }
            }
        }
        throw last == null
                ? new AiException(AiErrorType.UPSTREAM_ERROR, "没有可用的 AI 客户端")
                : last;
    }
}
