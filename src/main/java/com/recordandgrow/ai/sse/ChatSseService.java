package com.recordandgrow.ai.sse;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatStream;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.FinishReason;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.infra.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 把 ChatStream 转发成 SSE。
 *
 * <p>事件：start / delta / done / error；等待上游时每 15s 发一次注释心跳。
 * 客户端断开时通过 onCompletion/onError 关闭上游；写失败也会触发同样的清理。
 *
 * <p>取消与写入共用 {@link SseSender} 的锁：取消置位后再调用 send 会被拒绝，
 * 堵住「hasNext 返回 true 之后、send 之前」的窗口。
 */
@Service
public class ChatSseService {

    private static final Logger log = LoggerFactory.getLogger(ChatSseService.class);

    private static final ScheduledExecutorService HEARTBEAT =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "ai-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private static final java.util.concurrent.ExecutorService STREAM_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final ChatClient chatClient;
    private final Duration heartbeatInterval;
    private final Supplier<SseEmitter> emitterFactory;

    @Autowired
    public ChatSseService(ChatClient chatClient, AiProperties properties) {
        this(chatClient, properties.getChat().getTotalTimeout().plusSeconds(60), Duration.ofSeconds(15));
    }

    ChatSseService(ChatClient chatClient, Duration emitterTimeout, Duration heartbeatInterval) {
        this(chatClient, emitterTimeout, heartbeatInterval,
                () -> new SseEmitter(emitterTimeout.toMillis()));
    }

    ChatSseService(ChatClient chatClient, Duration emitterTimeout, Duration heartbeatInterval,
                   Supplier<SseEmitter> emitterFactory) {
        this.chatClient = chatClient;
        this.heartbeatInterval = heartbeatInterval;
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter stream(ChatRequest request) {
        return stream(request, UUID.randomUUID().toString(), null);
    }

    /**
     * 带来源的流式输出。
     *
     * <p>{@code sources} 在 {@code start} 之后、异步任务启动之前同步发送一次，
     * 因此顺序固定为 {@code start → sources → delta...}；即使后续上游失败，
     * 客户端也已经拿到来源。传 {@code null} 时行为与旧方法完全一致。
     */
    public SseEmitter stream(ChatRequest request, String requestId, SseEvents.Sources sources) {
        SseEmitter emitter = emitterFactory.get();

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean completedNormally = new AtomicBoolean(false);
        AtomicBoolean deltaSent = new AtomicBoolean(false);
        AtomicReference<ChatStream> streamRef = new AtomicReference<>();
        AtomicReference<FinishReason> finishReason = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>(ChatUsage.empty());
        SseSender sender = new SseSender(emitter, cancelled);

        Runnable cancel = () -> {
            if (completedNormally.get()) {
                return;
            }
            log.info("SSE cancelled: requestId={}, deltaSent={}", requestId, deltaSent.get());
            sender.cancel();
            closeQuietly(streamRef.get());
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());

        ScheduledFuture<?> heartbeat = HEARTBEAT.scheduleAtFixedRate(() -> {
            if (cancelled.get()) {
                return;
            }
            try {
                sender.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                cancel.run();
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);

        try {
            if (!sender.send(SseEmitter.event().name("start")
                    .data(new SseEvents.Start(requestId), MediaType.APPLICATION_JSON))) {
                heartbeat.cancel(false);
                sender.complete();
                return emitter;
            }
        } catch (IOException e) {
            heartbeat.cancel(false);
            sender.complete();
            return emitter;
        }

        if (sources != null) {
            try {
                if (!sender.send(SseEmitter.event().name("sources")
                        .data(sources, MediaType.APPLICATION_JSON))) {
                    heartbeat.cancel(false);
                    sender.complete();
                    return emitter;
                }
            } catch (IOException e) {
                heartbeat.cancel(false);
                sender.complete();
                return emitter;
            }
        }

        STREAM_EXECUTOR.execute(() -> {
            MDC.put("requestId", requestId);
            long startedAt = System.nanoTime();
            try {
                ChatStream stream = chatClient.stream(request);
                streamRef.set(stream);
                while (!cancelled.get() && stream.hasNext()) {
                    ChatChunk chunk = stream.next();
                    if (chunk.hasContent()) {
                        if (!deltaSent.getAndSet(true)) {
                            log.info("SSE first delta: requestId={}, firstTokenMs={}",
                                    requestId, elapsedMs(startedAt));
                        }
                        if (!sender.send(SseEmitter.event().name("delta")
                                .data(new SseEvents.Delta(chunk.contentDelta()), MediaType.APPLICATION_JSON))) {
                            return;
                        }
                    }
                    if (chunk.finishReason() != null) {
                        finishReason.set(chunk.finishReason());
                    }
                    if (chunk.usage() != null) {
                        usage.set(chunk.usage());
                    }
                }
                if (cancelled.get()) {
                    return;
                }
                FinishReason reason = finishReason.get();
                if (reason == FinishReason.INSUFFICIENT_RESOURCE || reason == FinishReason.ABORTED) {
                    if (!sendError(sender, ErrorCode.AI_UPSTREAM_ERROR, AiErrorType.UPSTREAM_ERROR,
                            deltaSent.get(), requestId)) {
                        sender.complete();
                        return;
                    }
                } else if (!sender.send(SseEmitter.event().name("done")
                        .data(new SseEvents.Done(reason == null ? "UNKNOWN" : reason.name(), usage.get()),
                                MediaType.APPLICATION_JSON))) {
                    sender.complete();
                    return;
                }
                ChatUsage doneUsage = usage.get();
                log.info("SSE done: requestId={}, totalMs={}, finishReason={}, "
                                + "promptTokens={}, completionTokens={}, totalTokens={}",
                        requestId, elapsedMs(startedAt), reason == null ? "UNKNOWN" : reason.name(),
                        doneUsage.promptTokens(), doneUsage.completionTokens(), doneUsage.totalTokens());
                completedNormally.set(true);
                sender.complete();
            } catch (AiException e) {
                log.warn("SSE 转发失败: requestId={}, type={}, provider={}, status={}",
                        requestId, e.getType(), e.getProvider(), e.getStatusCode());
                if (!cancelled.get()) {
                    try {
                        sendError(sender, codeFor(e.getType()), e.getType(), deltaSent.get(), requestId);
                    } catch (IOException ignored) {
                        cancelled.set(true);
                    }
                }
                if (!cancelled.get()) {
                    completedNormally.set(true);
                }
                sender.complete();
            } catch (IOException e) {
                // 写失败 = 客户端已经断开，不再尝试发 error。
                cancel.run();
                sender.complete();
            } catch (RuntimeException e) {
                // 上游/程序异常：客户端还在，就要发 error；与写失败区分开。
                log.warn("SSE 上游或程序异常: requestId={}", requestId, e);
                if (!cancelled.get()) {
                    try {
                        sendError(sender, ErrorCode.AI_UPSTREAM_ERROR, AiErrorType.UPSTREAM_ERROR,
                                deltaSent.get(), requestId);
                    } catch (IOException ignored) {
                        cancelled.set(true);
                    }
                }
                if (!cancelled.get()) {
                    completedNormally.set(true);
                }
                sender.complete();
            } finally {
                heartbeat.cancel(false);
                closeQuietly(streamRef.get());
                MDC.remove("requestId");
            }
        });
        return emitter;
    }

    private boolean sendError(SseSender sender, ErrorCode code, AiErrorType type,
                              boolean partial, String requestId) throws IOException {
        return sender.send(SseEmitter.event().name("error")
                .data(new SseEvents.Error(code.getCode(), type.name(), messageFor(type), partial, requestId),
                        MediaType.APPLICATION_JSON));
    }

    private static ErrorCode codeFor(AiErrorType type) {
        return switch (type) {
            case INVALID_REQUEST, BAD_RESPONSE, STREAM_INTERRUPTED -> ErrorCode.AI_UPSTREAM_ERROR;
            default -> ErrorCode.AI_UNAVAILABLE;
        };
    }

    private static String messageFor(AiErrorType type) {
        return switch (type) {
            case NOT_CONFIGURED -> "AI 服务未配置";
            case RATE_LIMITED -> "AI 服务请求过于频繁";
            case TIMEOUT -> "AI 服务响应超时";
            default -> "AI 服务暂时不可用";
        };
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static void closeQuietly(ChatStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (RuntimeException ignored) {
            // 关闭失败不影响清理流程
        }
    }

    /**
     * SseEmitter.send 的串行化封装：心跳线程和转发线程不会同时写。
     *
     * <p>取消状态与 send 共用同一把锁：cancel 置位后，已通过 hasNext 的那一个 delta
     * 也会在 send 入口被拒绝。
     */
    static final class SseSender {

        private final SseEmitter emitter;
        private final AtomicBoolean cancelled;

        SseSender(SseEmitter emitter, AtomicBoolean cancelled) {
            this.emitter = emitter;
            this.cancelled = cancelled;
        }

        synchronized boolean send(SseEmitter.SseEventBuilder event) throws IOException {
            if (cancelled.get()) {
                return false;
            }
            try {
                emitter.send(event);
                return true;
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IOException("SSE 写入失败", e);
            }
        }

        synchronized void complete() {
            emitter.complete();
        }

        synchronized void cancel() {
            cancelled.set(true);
        }
    }
}
