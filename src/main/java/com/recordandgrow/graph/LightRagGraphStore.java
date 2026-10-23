package com.recordandgrow.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordandgrow.config.GraphProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LightRAG 可选存储：把文档三元组拼成文本写入 LightRAG，检索时调用 /query。
 *
 * <p>LightRAG 的返回是自然语言 + references，不是结构化三元组；这里把它压成
 * {@link GraphHit}（source=LightRAG，relation=相关，target=回答摘要），
 * 作为「图检索」证据参与回答。路径和模式都可配置，兼容不同版本的服务端。
 *
 * <p>失败一律抛 {@link GraphStoreException}，由 FallbackGraphStore 回落到 Postgres。
 */
public class LightRagGraphStore implements GraphStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GraphProperties properties;
    private final HttpClient client;

    public LightRagGraphStore(GraphProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.getLightRag().getTimeout())
                .build();
    }

    @Override
    public void replaceDocument(Long documentId, List<GraphTriple> triples) {
        replaceDocument(documentId, "", triples);
    }

    @Override
    public void replaceDocument(Long documentId, String title, List<GraphTriple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (GraphTriple triple : triples) {
            if (triple.isValid()) {
                text.append(triple.source()).append(' ')
                        .append(triple.relation()).append(' ')
                        .append(triple.target()).append('\n');
            }
        }
        if (text.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text.toString());
        body.put("description", title == null ? "" : title);
        // LightRAG 1.5.x 的 /documents/text 需要 file_source；否则 400
        body.put("file_source", "recordandgrow://document/" + documentId);
        post(properties.getLightRag().getInsertPath(), body);
    }

    @Override
    public List<GraphHit> search(List<String> terms, int limit) {
        return search(String.join(" ", terms == null ? List.of() : terms), limit);
    }

    @Override
    public List<GraphHit> search(String question, List<String> terms, int limit) {
        String query = question != null && !question.isBlank()
                ? question
                : String.join(" ", terms == null ? List.of() : terms);
        return search(query, limit);
    }

    private List<GraphHit> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("mode", properties.getLightRag().getMode());
        JsonNode root = post(properties.getLightRag().getQueryPath(), body);

        int max = Math.max(1, limit);
        int maxChars = Math.max(100, properties.getLightRag().getMaxResponseChars());
        String response = root.path("response").asText("");

        // LightRAG 的 response 是整段答案，references 只是出处；不能每个 reference 都复制同一段
        // response 当成一条命中，否则 5 个引用会变成 5 条重复证据。
        List<String> titles = new ArrayList<>();
        JsonNode references = root.path("references");
        if (references.isArray()) {
            for (JsonNode reference : references) {
                String title = reference.path("file_path").asText(
                        reference.path("source").asText(""));
                if (!title.isBlank() && !titles.contains(title)) {
                    titles.add(title);
                }
            }
        }
        if (!response.isBlank()) {
            return List.of(new GraphHit(1L, "LightRAG", "相关",
                    truncate(response, maxChars), null, joinTitles(titles)));
        }

        List<GraphHit> hits = new ArrayList<>();
        if (references.isArray()) {
            for (JsonNode reference : references) {
                if (hits.size() >= max) {
                    break;
                }
                String content = reference.path("content").asText("");
                if (content.isBlank()) {
                    continue;
                }
                String title = reference.path("file_path").asText(
                        reference.path("source").asText("LightRAG"));
                hits.add(new GraphHit((long) hits.size() + 1, "LightRAG", "相关",
                        truncate(content, maxChars), null, title));
            }
        }
        return hits;
    }

    private static String joinTitles(List<String> titles) {
        if (titles.isEmpty()) {
            return "LightRAG";
        }
        int max = Math.min(3, titles.size());
        return String.join(", ", titles.subList(0, max));
    }

    @Override
    public void deleteDocument(Long documentId) {
        // LightRAG 没有按 documentId 删除的稳定 API；保留旧内容由重建/清理脚本负责
    }

    @Override
    public long countNodes() {
        return 0;
    }

    @Override
    public long countEdges() {
        return 0;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        GraphProperties.LightRag lightRag = properties.getLightRag();
        String url = trimTrailingSlash(lightRag.getUrl()) + normalizePath(path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(lightRag.getTimeout() == null ? Duration.ofSeconds(5) : lightRag.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            if (lightRag.getApiKey() != null && !lightRag.getApiKey().isBlank()) {
                builder.header("X-API-Key", lightRag.getApiKey());
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GraphStoreException("lightrag http " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(response.body());
        } catch (GraphStoreException e) {
            throw e;
        } catch (IOException e) {
            throw new GraphStoreException("lightrag io: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphStoreException("lightrag interrupted", e);
        } catch (RuntimeException e) {
            throw new GraphStoreException("lightrag failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:9621";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
