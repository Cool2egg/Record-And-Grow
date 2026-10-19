package com.recordandgrow.ai.support;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 轻量 JSON HTTP 封装：Bearer 认证、超时、错误映射。
 *
 * <p>不用 RestClient 的 exchange 做流式：exchange 的回调返回后响应会被关闭，
 * 无法把 InputStream 留给调用方后继续读。流式用 JDK HttpClient 的 sendAsync。
 */
public final class JsonHttpClient {

    private final String provider;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public JsonHttpClient(String provider, Duration connectTimeout, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public <T> T post(URI uri, String apiKey, Object payload, Class<T> responseType, Duration timeout) {
        requireApiKey(apiKey);
        HttpRequest request = requestBuilder(uri, apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw error(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null));
            }
            try {
                return mapper.readValue(response.body(), responseType);
            } catch (RuntimeException e) {
                throw new AiException(AiErrorType.BAD_RESPONSE,
                        provider + " 响应解析失败", provider, response.statusCode(), null, e);
            }
        } catch (AiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiErrorType.NETWORK, provider + " 请求被中断", provider, null, null, e);
        } catch (IOException e) {
            throw new AiException(AiErrorType.NETWORK, provider + " 网络异常: " + e.getMessage(), provider, null, null, e);
        }
    }

    /**
     * 发起流式请求。返回时响应头已到达且状态码为 2xx；body 的 Publisher 由调用方订阅并消费。
     */
    public HttpResponse<Flow.Publisher<List<ByteBuffer>>> postStreamPublisher(
            URI uri, String apiKey, Object payload, Duration firstTokenTimeout) {
        requireApiKey(apiKey);
        HttpRequest request = requestBuilder(uri, apiKey)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher());
        try {
            HttpResponse<Flow.Publisher<List<ByteBuffer>>> response =
                    future.get(firstTokenTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() / 100 != 2) {
                String body = collect(response.body(), Duration.ofSeconds(5));
                throw error(response.statusCode(), body,
                        response.headers().firstValue("Retry-After").orElse(null));
            }
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiException(AiErrorType.TIMEOUT, provider + " 等待响应超时", provider, null, null, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiException(AiErrorType.NETWORK, provider + " 请求被中断", provider, null, null, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AiException(AiErrorType.NETWORK, provider + " 网络异常: " + cause.getMessage(), provider, null, null, cause);
        }
    }

    private String collect(Flow.Publisher<List<ByteBuffer>> publisher, Duration timeout) {
        CompletableFuture<String> future = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    out.writeBytes(bytes);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(out.toString(StandardCharsets.UTF_8));
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    public AiException error(int status, String body, String retryAfterHeader) {
        return OpenAiErrorParser.toException(mapper, provider, status, body, retryAfterHeader);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new AiException(AiErrorType.BAD_RESPONSE, "请求序列化失败", provider, null, null, e);
        }
    }

    private HttpRequest.Builder requestBuilder(URI uri, String apiKey) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
    }

    private void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiException(AiErrorType.NOT_CONFIGURED, provider + " API key 未配置", provider, null, null, null);
        }
    }
}
