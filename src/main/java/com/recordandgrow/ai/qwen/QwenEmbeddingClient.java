package com.recordandgrow.ai.qwen;

import com.recordandgrow.ai.AiCallObserver;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.ai.support.JsonHttpClient;
import com.recordandgrow.ai.support.RetryPolicy;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qwen text-embedding-v4 客户端。
 *
 * <p>单请求最多 10 条，内部自动分批；响应按 index 重排，保证与输入顺序一致。
 */
public final class QwenEmbeddingClient implements EmbeddingClient {

    private final AiProperties.Embedding props;
    private final JsonHttpClient http;
    private final AiCallObserver observer;
    private final URI embeddingUri;
    private final String clientName;

    public QwenEmbeddingClient(AiProperties.Embedding props, ObjectMapper mapper, AiCallObserver observer) {
        this.props = props;
        this.http = new JsonHttpClient("qwen", props.getConnectTimeout(), mapper);
        this.observer = observer;
        this.embeddingUri = URI.create(stripTrailingSlash(props.getBaseUrl()) + "/embeddings");
        this.clientName = "qwen/" + props.getModel();
    }

    /** 入库路径：沿用 {@code ai.embedding.timeout} / {@code max-attempts}，行为与模块五一致。 */
    @Override
    public List<float[]> embed(List<String> texts) {
        return embed(texts, props.getTimeout(), props.getMaxAttempts());
    }

    /**
     * 查询路径：超时与尝试次数按次传入（模块六默认 8s / 1 次）。
     *
     * <p>连接超时不在按次范围内，仍沿用 {@code ai.embedding.connect-timeout}。
     */
    @Override
    public List<float[]> embed(List<String> texts, Duration timeout, int maxAttempts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new AiException(AiErrorType.INVALID_REQUEST, "texts 不能包含空文本");
            }
        }
        Duration requestTimeout = timeout == null ? props.getTimeout() : timeout;
        RetryPolicy policy = new RetryPolicy(maxAttempts,
                Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(5));
        long started = System.nanoTime();
        int batchSize = Math.max(1, props.getBatchSize());
        List<float[]> vectors = new ArrayList<>(texts.size());
        ChatUsage totalUsage = ChatUsage.empty();
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            QwenWire.EmbeddingResponse response = callBatch(batch, requestTimeout, policy);
            vectors.addAll(toVectors(response, batch.size()));
            totalUsage = add(totalUsage, toUsage(response.usage()));
        }
        observer.onEmbeddingComplete(texts.size(), props.getDimensions(), totalUsage, elapsed(started));
        return vectors;
    }

    @Override
    public int dimensions() {
        return props.getDimensions();
    }

    /** 只判断「有没有配置 key」；key 填错仍然要等真实调用才知道。 */
    @Override
    public boolean isConfigured() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    private QwenWire.EmbeddingResponse callBatch(List<String> batch, Duration timeout, RetryPolicy policy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("input", batch);
        body.put("dimensions", props.getDimensions());
        body.put("encoding_format", "float");

        AiException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                return http.post(embeddingUri, props.getApiKey(), body,
                        QwenWire.EmbeddingResponse.class, timeout);
            } catch (AiException e) {
                last = e;
                if (!policy.canRetry(e, attempt)) {
                    throw e;
                }
                policy.pause(policy.backoff(e, attempt));
            }
        }
        throw last == null ? new AiException(AiErrorType.UPSTREAM_ERROR, "Qwen embedding 调用失败") : last;
    }

    private List<float[]> toVectors(QwenWire.EmbeddingResponse response, int expected) {
        if (response == null || response.data() == null || response.data().size() != expected) {
            throw new AiException(AiErrorType.BAD_RESPONSE, "Qwen embedding 返回条数与输入不一致");
        }
        float[][] ordered = new float[expected][];
        for (QwenWire.Item item : response.data()) {
            if (item == null || item.index() < 0 || item.index() >= expected) {
                throw new AiException(AiErrorType.BAD_RESPONSE, "Qwen embedding 返回了非法 index");
            }
            if (ordered[item.index()] != null) {
                throw new AiException(AiErrorType.BAD_RESPONSE, "Qwen embedding 返回了重复 index");
            }
            if (item.embedding() == null || item.embedding().length != props.getDimensions()) {
                throw new AiException(AiErrorType.BAD_RESPONSE, "Qwen embedding 维度与配置不一致");
            }
            ordered[item.index()] = item.embedding();
        }
        List<float[]> result = new ArrayList<>(expected);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new AiException(AiErrorType.BAD_RESPONSE, "Qwen embedding 缺少 index");
            }
            result.add(vector);
        }
        return result;
    }

    private ChatUsage toUsage(QwenWire.Usage usage) {
        if (usage == null) {
            return ChatUsage.empty();
        }
        int prompt = usage.promptTokens() == null ? 0 : usage.promptTokens();
        int total = usage.totalTokens() == null ? prompt : usage.totalTokens();
        return new ChatUsage(prompt, 0, total);
    }

    private ChatUsage add(ChatUsage a, ChatUsage b) {
        return new ChatUsage(
                a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                a.totalTokens() + b.totalTokens());
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
