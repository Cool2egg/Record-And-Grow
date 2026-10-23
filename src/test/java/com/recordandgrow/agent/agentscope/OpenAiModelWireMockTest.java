package com.recordandgrow.agent.agentscope;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 OpenAI 协议链路：AgentScope 的 OpenAIChatModel 打 WireMock，
 * 验证 DeepSeek 兼容端点 + 流式 tool_calls 能被正确解析并驱动 ReAct。
 */
class OpenAiModelWireMockTest {

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
    void modelShouldParseStreamingToolCallThenAnswer() {
        server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("react")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"echo","arguments":"{\\"text\\":\\"hi\\"}"}}]},"finish_reason":null}]}

                                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                                data: [DONE]

                                """))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("react")
                .whenScenarioStateIs("second")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":2,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":null}]}

                                data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":2,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

                                data: [DONE]

                                """)));

        Model model = OpenAIChatModel.builder()
                .apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + server.port())
                .endpointPath("/v1/chat/completions")
                .modelName("deepseek-flash")
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTools());

        try (ReActAgent agent = ReActAgent.builder()
                .name("wire")
                .sysPrompt("先调用工具，再回答。")
                .model(model)
                .toolkit(toolkit)
                .maxIters(3)
                .build()) {
            Msg result = agent.call("echo hi", RuntimeContext.empty())
                    .block(Duration.ofSeconds(10));

            assertThat(result).isNotNull();
            assertThat(result.getTextContent()).isEqualTo("done");
            server.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
            server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                    .withRequestBody(containing("\"tool\"")));
        }
    }

    static class EchoTools {

        @Tool(name = "echo", description = "回显输入文本")
        public String echo(@ToolParam(name = "text", description = "要回显的文本") String text) {
            return "echo:" + text;
        }
    }
}
