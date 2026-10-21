package com.recordandgrow.ai.qwen;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.NoopAiCallObserver;
import com.recordandgrow.ai.config.AiProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 {@link EmbeddingClient} 的按次重载：
 *
 * <ol>
 *   <li>没覆写的实现仍走旧 {@code embed(List)}（接口默认方法）；</li>
 *   <li>{@code QwenEmbeddingClient} 的旧方法行为不变（配置的 30s / 2 次）；</li>
 *   <li>新重载按传入的超时与尝试次数生效（8s / 1 次是查询链路的默认）。</li>
 * </ol>
 *
 * <p>与 {@code QwenEmbeddingClientTest} 分开，保持那 3 个旧用例零改动。
 */
class QwenEmbeddingClientOverloadTest {

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
    void implementationWithoutOverrideFallsBackToOldEmbed() {
        RecordingEmbeddingClient client = new RecordingEmbeddingClient();

        List<float[]> vectors = client.embed(List.of("查询"), Duration.ofMillis(200), 1);

        assertThat(vectors).hasSize(1);
        assertThat(client.oldCalls.get()).as("默认方法必须委托旧 embed，而不是另开路径").isEqualTo(1);
    }

    @Test
    void oldEmbedStillUsesConfiguredAttempts() {
        server.stubFor(post(urlEqualTo("/embeddings"))
                .inScenario("old-path")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("ok"));
        server.stubFor(post(urlEqualTo("/embeddings"))
                .inScenario("old-path")
                .whenScenarioStateIs("ok")
                .willReturn(okJson(response())));

        // 配置 2 次尝试：第一次 500 后重试一次成功
        assertThat(client(2).embed(List.of("t0"))).hasSize(1);
        server.verify(2, postRequestedFor(urlEqualTo("/embeddings")));
    }

    @Test
    void queryOverloadDoesNotRetry() {
        server.stubFor(post(urlEqualTo("/embeddings")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client(2).embed(List.of("t0"), Duration.ofSeconds(2), 1))
                .isInstanceOfSatisfying(AiException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiErrorType.UPSTREAM_ERROR));

        server.verify(1, postRequestedFor(urlEqualTo("/embeddings")));
    }

    @Test
    void queryOverloadHonoursItsOwnTimeout() {
        server.stubFor(post(urlEqualTo("/embeddings"))
                .willReturn(okJson(response()).withFixedDelay(2_000)));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client(2).embed(List.of("t0"), Duration.ofMillis(200), 1))
                .isInstanceOf(AiException.class);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("必须按 200ms 截断，而不是配置里的 5s").isLessThan(1_500);
        server.verify(1, postRequestedFor(urlEqualTo("/embeddings")));
    }

    private QwenEmbeddingClient client(int maxAttempts) {
        AiProperties.Embedding props = new AiProperties.Embedding();
        props.setBaseUrl("http://localhost:" + server.port());
        props.setApiKey("test-key");
        props.setModel("text-embedding-v4");
        props.setDimensions(4);
        props.setBatchSize(10);
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setTimeout(Duration.ofSeconds(5));
        props.setMaxAttempts(maxAttempts);
        return new QwenEmbeddingClient(props, new ObjectMapper(), new NoopAiCallObserver());
    }

    private static String response() {
        return """
                {"data":[{"embedding":[1.0,2.0,3.0,4.0],"index":0}],
                 "usage":{"prompt_tokens":1,"total_tokens":1}}
                """;
    }

    /** 只实现旧方法；用来证明接口默认方法的委托路径。 */
    private static final class RecordingEmbeddingClient implements EmbeddingClient {

        private final AtomicInteger oldCalls = new AtomicInteger();

        @Override
        public List<float[]> embed(List<String> texts) {
            oldCalls.incrementAndGet();
            return List.of(new float[]{1f, 2f, 3f, 4f});
        }

        @Override
        public int dimensions() {
            return 4;
        }
    }
}
