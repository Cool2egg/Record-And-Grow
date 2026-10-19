package com.recordandgrow.ai.qwen;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.NoopAiCallObserver;
import com.recordandgrow.ai.config.AiProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenEmbeddingClientTest {

    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void batchesAndSortsByIndex() {
        server.stubFor(post(urlEqualTo("/embeddings"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("t0")))
                .willReturn(okJson(response(100, 10))));
        server.stubFor(post(urlEqualTo("/embeddings"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("t10")))
                .willReturn(okJson(response(200, 10))));
        server.stubFor(post(urlEqualTo("/embeddings"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("t20")))
                .willReturn(okJson(response(300, 5))));

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            texts.add("t" + i);
        }

        List<float[]> vectors = client(4).embed(texts);

        assertThat(vectors).hasSize(25);
        assertThat(vectors.get(0)[0]).isEqualTo(100f);
        assertThat(vectors.get(9)[0]).isEqualTo(109f);
        assertThat(vectors.get(10)[0]).isEqualTo(200f);
        assertThat(vectors.get(19)[0]).isEqualTo(209f);
        assertThat(vectors.get(20)[0]).isEqualTo(300f);
        assertThat(vectors.get(24)[0]).isEqualTo(304f);
        server.verify(3, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlEqualTo("/embeddings")));
    }

    @Test
    void retries429() {
        server.stubFor(post(urlEqualTo("/embeddings"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok"));
        server.stubFor(post(urlEqualTo("/embeddings"))
                .inScenario("retry")
                .whenScenarioStateIs("ok")
                .willReturn(okJson(response(100, 1))));

        assertThat(client(4).embed(List.of("t0"))).hasSize(1);
        server.verify(2, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlEqualTo("/embeddings")));
    }

    @Test
    void rejectsWrongDimension() {
        server.stubFor(post(urlEqualTo("/embeddings")).willReturn(okJson("""
                {"data":[{"embedding":[1.0,2.0,3.0],"index":0}],
                 "usage":{"prompt_tokens":1,"total_tokens":1}}
                """)));

        assertThatThrownBy(() -> client(4).embed(List.of("t0")))
                .isInstanceOfSatisfying(AiException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiErrorType.BAD_RESPONSE));
    }

    private QwenEmbeddingClient client(int dimensions) {
        AiProperties.Embedding props = new AiProperties.Embedding();
        props.setBaseUrl("http://localhost:" + server.port());
        props.setApiKey("test-key");
        props.setModel("text-embedding-v4");
        props.setDimensions(dimensions);
        props.setBatchSize(10);
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setTimeout(Duration.ofSeconds(5));
        props.setMaxAttempts(2);
        return new QwenEmbeddingClient(props, new ObjectMapper(), new NoopAiCallObserver());
    }

    /**
     * 故意按 index 倒序返回，验证客户端会重排。
     */
    private static String response(int base, int count) {
        StringBuilder json = new StringBuilder("{\"data\":[");
        for (int index = count - 1; index >= 0; index--) {
            if (index < count - 1) {
                json.append(',');
            }
            json.append("{\"embedding\":[").append(base + index)
                    .append(",1.0,2.0,3.0],\"index\":").append(index).append('}');
        }
        json.append("],\"usage\":{\"prompt_tokens\":10,\"total_tokens\":10}}");
        return json.toString();
    }
}
