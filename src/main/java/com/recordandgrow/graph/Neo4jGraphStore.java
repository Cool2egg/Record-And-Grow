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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 可选存储：走 HTTP 事务端点，不引 Neo4j Java Driver。
 *
 * <p>图模型：{@code (:RecordAndGrow:Entity {documentId, normalized, name})}，
 * 关系 {@code -[:RELATED {documentId, relation, title}]->}。
 * 写入是按文档整体替换；检索是实体名 CONTAINS + 1 跳。
 */
public class Neo4jGraphStore implements GraphStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LABEL = "RecordAndGrow";

    private final GraphProperties properties;
    private final HttpClient client;

    public Neo4jGraphStore(GraphProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.getNeo4j().getTimeout())
                .build();
    }

    @Override
    public void replaceDocument(Long documentId, List<GraphTriple> triples) {
        replaceDocument(documentId, "", triples);
    }

    @Override
    public void replaceDocument(Long documentId, String title, List<GraphTriple> triples) {
        List<Map<String, Object>> statements = new ArrayList<>();
        statements.add(statement("MATCH (n:" + LABEL + " {documentId: $documentId}) DETACH DELETE n",
                Map.of("documentId", documentId)));
        if (triples != null) {
            for (GraphTriple triple : triples) {
                if (!triple.isValid()) {
                    continue;
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("documentId", documentId);
                params.put("source", triple.source());
                params.put("sourceNormalized", normalize(triple.source()));
                params.put("target", triple.target());
                params.put("targetNormalized", normalize(triple.target()));
                params.put("relation", triple.relation());
                params.put("title", title == null ? "" : title);
                statements.add(statement("""
                        MERGE (s:%s:Entity {documentId: $documentId, normalized: $sourceNormalized})
                        SET s.name = $source
                        MERGE (t:%s:Entity {documentId: $documentId, normalized: $targetNormalized})
                        SET t.name = $target
                        MERGE (s)-[r:RELATED {documentId: $documentId, relation: $relation}]->(t)
                        SET r.title = $title
                        """.formatted(LABEL, LABEL), params));
            }
        }
        execute(statements);
    }

    @Override
    public List<GraphHit> search(List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<String> normalized = terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(Neo4jGraphStore::normalize)
                .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }
        JsonNode response = execute(List.of(statement("""
                UNWIND $terms AS term
                MATCH (s:Entity)-[r:RELATED]->(t:Entity)
                WHERE s.normalized CONTAINS term OR t.normalized CONTAINS term
                RETURN DISTINCT elementId(r), s.name, r.relation, t.name, r.documentId, r.title
                LIMIT $limit
                """, Map.of("terms", normalized, "limit", Math.max(1, limit)))));

        List<GraphHit> hits = new ArrayList<>();
        for (JsonNode row : rows(response)) {
            JsonNode idNode = row.path(0);
            long edgeId = idNode.isNumber()
                    ? idNode.asLong()
                    : Math.abs((long) idNode.asText("").hashCode());
            hits.add(new GraphHit(
                    edgeId,
                    row.path(1).asText(""),
                    row.path(2).asText(""),
                    row.path(3).asText(""),
                    row.path(4).isNull() || row.path(4).isMissingNode() ? null : row.path(4).asLong(),
                    row.path(5).asText("")));
        }
        return hits;
    }

    @Override
    public void deleteDocument(Long documentId) {
        execute(List.of(statement("MATCH (n:" + LABEL + " {documentId: $documentId}) DETACH DELETE n",
                Map.of("documentId", documentId))));
    }

    @Override
    public long countNodes() {
        return count("MATCH (n:Entity) RETURN count(n)");
    }

    @Override
    public long countEdges() {
        return count("MATCH ()-[r:RELATED]->() RETURN count(r)");
    }

    private long count(String cypher) {
        JsonNode response = execute(List.of(statement(cypher, Map.of())));
        for (JsonNode row : rows(response)) {
            return row.path(0).asLong();
        }
        return 0;
    }

    private JsonNode execute(List<Map<String, Object>> statements) {
        GraphProperties.Neo4j neo4j = properties.getNeo4j();
        String baseUrl = trimTrailingSlash(neo4j.getUrl());
        String url = baseUrl + "/db/" + neo4j.getDatabase() + "/tx/commit";
        try {
            String body = JSON.writeValueAsString(Map.of("statements", statements));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(neo4j.getTimeout() == null ? Duration.ofSeconds(3) : neo4j.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (neo4j.getUsername() != null && !neo4j.getUsername().isBlank()) {
                String token = neo4j.getUsername() + ":" + (neo4j.getPassword() == null ? "" : neo4j.getPassword());
                builder.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GraphStoreException("neo4j http " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                throw new GraphStoreException("neo4j error: " + errors.get(0).path("message").asText(""));
            }
            return root;
        } catch (GraphStoreException e) {
            throw e;
        } catch (IOException e) {
            throw new GraphStoreException("neo4j io: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphStoreException("neo4j interrupted", e);
        } catch (RuntimeException e) {
            throw new GraphStoreException("neo4j failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static Map<String, Object> statement(String cypher, Map<String, Object> parameters) {
        return Map.of("statement", cypher, "parameters", parameters);
    }

    private static List<JsonNode> rows(JsonNode root) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            for (JsonNode data : result.path("data")) {
                rows.add(data.path("row"));
            }
        }
        return rows;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:7474";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
