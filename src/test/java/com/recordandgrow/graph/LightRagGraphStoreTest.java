package com.recordandgrow.graph;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.recordandgrow.config.GraphProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagGraphStoreTest {

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
    void searchShouldReturnSingleAnswerWithReferenceTitles() {
        server.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":"Kafka 属于消息队列",
                                 "references":[{"file_path":"kafka.md","content":"Kafka 是分布式消息队列"},
                                                {"file_path":"mq.md","content":"消息队列是基础设施"}]}
                                """)));

        List<GraphHit> hits = store().search(List.of("kafka"), 5);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.source()).isEqualTo("LightRAG");
            assertThat(hit.target()).isEqualTo("Kafka 属于消息队列");
            assertThat(hit.title()).contains("kafka.md").contains("mq.md");
        });
    }

    @Test
    void searchShouldUseReferenceContentWhenResponseIsEmpty() {
        server.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":"",
                                 "references":[{"file_path":"kafka.md","content":"Kafka 是分布式消息队列"}]}
                                """)));

        List<GraphHit> hits = store().search(List.of("kafka"), 5);

        assertThat(hits).singleElement().satisfies(hit ->
                assertThat(hit.target()).contains("Kafka 是分布式消息队列"));
    }

    @Test
    void rawQuestionShouldBeUsedInsteadOfBigramTerms() {
        server.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"response\":\"回答\"}")));

        store().search("Kafka 和学习有什么关系", List.of("kafka", "习和", "和什"), 5);

        server.verify(postRequestedFor(urlEqualTo("/query"))
                .withRequestBody(containing("Kafka 和学习有什么关系")));
    }

    @Test
    void searchShouldFallbackToResponseWithoutReferences() {
        server.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"response\":\"只有一段回答\"}")));

        assertThat(store().search(List.of("kafka"), 5)).singleElement()
                .satisfies(hit -> assertThat(hit.target()).isEqualTo("只有一段回答"));
    }

    @Test
    void replaceDocumentShouldPostTextAndTitle() {
        server.stubFor(post(urlEqualTo("/documents/text"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"ok\"}")));

        store().replaceDocument(7L, "Kafka 笔记",
                List.of(new GraphTriple("Kafka", "属于", "消息队列")));

        server.verify(postRequestedFor(urlEqualTo("/documents/text"))
                .withRequestBody(containing("Kafka 属于 消息队列"))
                .withRequestBody(containing("Kafka 笔记")));
    }

    @Test
    void serverErrorShouldThrowGraphStoreException() {
        server.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> store().search(List.of("kafka"), 5))
                .isInstanceOf(GraphStoreException.class)
                .hasMessageContaining("500");
    }

    private LightRagGraphStore store() {
        GraphProperties properties = new GraphProperties();
        properties.getLightRag().setUrl("http://127.0.0.1:" + server.port());
        properties.getLightRag().setTimeout(java.time.Duration.ofSeconds(2));
        return new LightRagGraphStore(properties);
    }
}
