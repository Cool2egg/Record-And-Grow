package com.recordandgrow.ai.deepseek;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatStream;
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
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekChatClientTest {

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
    void nonStreamingSuccessSendsThinkingDisabled() {
        server.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
                {"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
                """)));

        ChatResponse response = client(2000).complete(request());

        assertThat(response.content()).isEqualTo("你好");
        assertThat(response.usage().totalTokens()).isEqualTo(7);
        server.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.model", equalTo("deepseek-flash")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.thinking.type", equalTo("disabled"))));
    }

    @Test
    void maps401ToAuthentication() {
        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"bad key\"}}")));

        assertThatThrownBy(() -> client(2000).complete(request()))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.getType()).isEqualTo(AiErrorType.AUTHENTICATION);
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void maps429ToRateLimitedAndKeepsRetryAfter() {
        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2")));

        assertThatThrownBy(() -> client(2000).complete(request()))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.getType()).isEqualTo(AiErrorType.RATE_LIMITED);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Test
    void maps500ToRetryableUpstreamError() {
        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client(2000).complete(request()))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.getType()).isEqualTo(AiErrorType.UPSTREAM_ERROR);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void streamsContentUntilDone() {
        String body = """
                : keep-alive

                data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                data: [DONE]

                """;
        server.stubFor(post(urlEqualTo("/chat/completions")).willReturn(
                aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body)));

        List<String> contents = new ArrayList<>();
        try (ChatStream stream = client(2000).stream(request())) {
            while (stream.hasNext()) {
                ChatChunk chunk = stream.next();
                if (chunk.hasContent()) {
                    contents.add(chunk.contentDelta());
                }
            }
        }
        assertThat(String.join("", contents)).isEqualTo("你好");
    }

    @Test
    void streamFirstTokenTimeoutFires() {
        server.stubFor(post(urlEqualTo("/chat/completions")).willReturn(
                aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withFixedDelay(2000)
                        .withBody("data: [DONE]\n\n")));

        assertThatThrownBy(() -> client(200).stream(request()))
                .isInstanceOfSatisfying(AiException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiErrorType.TIMEOUT));
    }

    private ChatRequest request() {
        return new ChatRequest(List.of(new ChatMessage(ChatMessage.Role.USER, "hello")), 128, 0.3);
    }

    private DeepSeekChatClient client(long firstTokenTimeoutMillis) {
        AiProperties.Chat props = new AiProperties.Chat();
        props.setBaseUrl("http://localhost:" + server.port());
        props.setApiKey("test-key");
        props.setModel("deepseek-flash");
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setIdleTimeout(Duration.ofSeconds(2));
        props.setFirstTokenTimeout(Duration.ofMillis(firstTokenTimeoutMillis));
        props.setTotalTimeout(Duration.ofSeconds(5));
        return new DeepSeekChatClient(props, props.getModel(), new ObjectMapper(), new NoopAiCallObserver());
    }
}
