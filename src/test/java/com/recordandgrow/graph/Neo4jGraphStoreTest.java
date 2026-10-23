package com.recordandgrow.graph;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.recordandgrow.config.GraphProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Neo4j HTTP 事务端点协议测试：不连真 Neo4j，只验证 Cypher 请求形状、Basic Auth 与行解析。
 */
class Neo4jGraphStoreTest {

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void replaceDocumentShouldSendCypherWithAuth() {
        server.stubFor(post(urlEqualTo("/db/neo4j/tx/commit"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"results\":[],\"errors\":[]}")));

        store().replaceDocument(7L, "Kafka 笔记",
                List.of(new GraphTriple("Kafka", "属于", "消息队列")));

        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("neo4j:pass".getBytes(StandardCharsets.UTF_8));
        server.verify(postRequestedFor(urlEqualTo("/db/neo4j/tx/commit"))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withRequestBody(containing("MERGE"))
                .withRequestBody(containing("RELATED"))
                .withRequestBody(containing("Kafka")));
    }

    @Test
    void searchShouldParseRows() {
        server.stubFor(post(urlEqualTo("/db/neo4j/tx/commit"))
                .withRequestBody(containing("UNWIND"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"results":[{"columns":["id(r)","s.name","r.relation","t.name","r.documentId","r.title"],
                                "data":[{"row":[1,"Kafka","属于","消息队列",7,"Kafka 笔记"]}]}],"errors":[]}
                                """)));

        List<GraphHit> hits = store().search(List.of("kafka"), 5);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.edgeId()).isEqualTo(1L);
            assertThat(hit.source()).isEqualTo("Kafka");
            assertThat(hit.relation()).isEqualTo("属于");
            assertThat(hit.target()).isEqualTo("消息队列");
            assertThat(hit.documentId()).isEqualTo(7L);
            assertThat(hit.title()).isEqualTo("Kafka 笔记");
        });
    }

    @Test
    void countShouldReadFirstRow() {
        server.stubFor(post(urlEqualTo("/db/neo4j/tx/commit"))
                .withRequestBody(containing("count(n)"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"results\":[{\"data\":[{\"row\":[3]}]}],\"errors\":[]}")));

        assertThat(store().countNodes()).isEqualTo(3);
    }

    private Neo4jGraphStore store() {
        GraphProperties properties = new GraphProperties();
        properties.getNeo4j().setUrl("http://127.0.0.1:" + server.port());
        properties.getNeo4j().setUsername("neo4j");
        properties.getNeo4j().setPassword("pass");
        return new Neo4jGraphStore(properties);
    }
}
