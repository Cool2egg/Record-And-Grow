package com.recordandgrow.ai.rerank;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.support.JsonHttpClient;
import com.recordandgrow.config.RerankProperties;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼 gte-rerank 客户端。
 *
 * <p>响应手工按 Map 解析而不是建 DTO：只需要 {@code output.results[].index/relevance_score}
 * 两个字段，建 DTO 反而把供应商的多余字段（usage、id）绑进类型里。
 *
 * <p>失败一律转 {@link AiException}，由调用方决定"保留原序"——精排不该有自己的一套错误处理。
 */
public final class DashScopeRerankClient implements RerankClient {

    private final RerankProperties properties;
    private final JsonHttpClient http;
    private final URI uri;

    public DashScopeRerankClient(RerankProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.http = new JsonHttpClient("dashscope-rerank", properties.getConnectTimeout(), mapper);
        this.uri = URI.create(properties.getBaseUrl());
    }

    @Override
    public boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RerankScore> rerank(String query, List<String> documents, int topN) {
        Map<String, Object> payload = Map.of(
                "model", properties.getModel(),
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("return_documents", false, "top_n", Math.max(1, topN)));

        Map<String, Object> body = http.post(uri, properties.getApiKey(), payload, Map.class, properties.getTimeout());
        return parse(body);
    }

    @SuppressWarnings("unchecked")
    static List<RerankScore> parse(Map<String, Object> body) {
        Object output = body == null ? null : body.get("output");
        Object results = output instanceof Map<?, ?> map ? map.get("results") : null;
        if (!(results instanceof List<?> list)) {
            throw new AiException(AiErrorType.BAD_RESPONSE, "rerank 响应缺少 output.results");
        }
        List<RerankScore> scores = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Object index = row.get("index");
            Object score = row.get("relevance_score");
            if (index instanceof Number i && score instanceof Number s) {
                scores.add(new RerankScore(i.intValue(), s.doubleValue()));
            }
        }
        return scores;
    }
}
