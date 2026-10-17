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

/**
 * 把 ChatStream 转发成 SSE。
 *
 * <p>事件：start / delta / done / error；等待上游时每 15s 发一次注释心跳。
 * 客户端断开时通过 onCompletion/onError 关闭上游；写失败也会触发同样的清理。
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
    private final Duration emitterTimeout;
    private final Duration heartbeatInterval;

    @Autowired
    public ChatSseService(ChatClient chatClient, AiProperties properties) {
        this(chatClient, properties.getChat().getTotalTimeout().plusSeconds(60), Duration.ofSeconds(15));
    }

    ChatSseService(ChatClient chatClient, Duration emitterTimeout, Duration heartbeatInterval) {
        this.chatClient = chatClient;
        this.emitterTimeout = emitterTimeout;
        this.heartbeatInterval = heartbeatInterval;
    }

    public SseEmitter stream(ChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(emitterTimeout.toMillis());
        SseSender sender = new SseSender(emitter);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean deltaSent = new AtomicBoolean(false);
        AtomicReference<ChatStream> streamRef = new AtomicReference<>();
        AtomicReference<FinishReason> finishReason = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>(ChatUsage.empty());

        Runnable cancel = () -> {
            cancelled.set(true);
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
            } catch (IOException | RuntimeException e) {
                cancel.run();
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);

        try {
            sender.send(SseEmitter.event().name("start")
                    .data(new SseEvents.Start(requestId), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            heartbeat.cancel(false);
            sender.complete();
            return emitter;
        }

        STREAM_EXECUTOR.execute(() -> {
            MDC.put("requestId", requestId);
            try {
                ChatStream stream = chatClient.stream(request);
                streamRef.set(stream);
                while (!cancelled.get() && stream.hasNext()) {
                    ChatChunk chunk = stream.next();
                    if (chunk.hasContent()) {
                        deltaSent.set(true);
                        sender.send(SseEmitter.event().name("delta")
                                .data(new SseEvents.Delta(chunk.contentDelta()), MediaType.APPLICATION_JSON));
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
                    sendError(sender, ErrorCode.AI_UPSTREAM_ERROR, AiErrorType.UPSTREAM_ERROR,
                            deltaSent.get(), requestId);
                } else {
                    sender.send(SseEmitter.event().name("done")
                            .data(new SseEvents.Done(reason == null ? "UNKNOWN" : reason.name(), usage.get()),
                                    MediaType.APPLICATION_JSON));
                }
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
                sender.complete();
            } catch (IOException e) {
                cancel.run();
                sender.complete();
            } catch (RuntimeException e) {
                log.debug("SSE 写入失败: requestId={}", requestId, e);
                cancel.run();
                sender.complete();
            } finally {
                heartbeat.cancel(false);
                closeQuietly(streamRef.get());
                MDC.remove("requestId");
            }
        });
        return emitter;
    }

    private void sendError(SseSender sender, ErrorCode code, AiErrorType type,
                           boolean partial, String requestId) throws IOException {
        sender.send(SseEmitter.event().name("error")
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
     */
    private static final class SseSender {

        private final SseEmitter emitter;

        private SseSender(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private synchronized void send(SseEmitter.SseEventBuilder event) throws IOException {
            emitter.send(event);
        }

        private synchronized void complete() {
            emitter.complete();
        }
    }
}
