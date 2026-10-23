package com.recordandgrow.agent.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentScope 接入冒烟：不碰网络，验证「ReAct 循环 + 反射注册工具」这套 API
 * 与当前 JDK / Spring Boot 4 的依赖组合能跑通。后续所有编排都建立在这条链路上。
 */
class AgentScopeSmokeTest {

    @Test
    void reactAgentShouldCallRegisteredToolThenAnswer() {
        AtomicInteger calls = new AtomicInteger();
        Model model = new ScriptedModel(calls);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTools());

        try (ReActAgent agent = ReActAgent.builder()
                .name("smoke")
                .sysPrompt("先调用工具，再回答。")
                .model(model)
                .toolkit(toolkit)
                .maxIters(3)
                .build()) {
            Msg result = agent.call("echo hi", RuntimeContext.empty())
                    .block(Duration.ofSeconds(5));

            assertThat(result).isNotNull();
            assertThat(result.getTextContent()).isEqualTo("done");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void toolkitShouldExposeToolSchema() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTools());
        assertThat(toolkit.getToolNames()).contains("echo");
        assertThat(toolkit.getToolSchemas()).singleElement()
                .satisfies(schema -> assertThat(schema.getName()).isEqualTo("echo"));
    }

    static class EchoTools {

        @Tool(name = "echo", description = "回显输入文本")
        public String echo(@ToolParam(name = "text", description = "要回显的文本") String text) {
            return "echo:" + text;
        }
    }

    /** 第一轮返回一次工具调用，第二轮返回最终文本。 */
    static class ScriptedModel implements Model {

        private final AtomicInteger calls;

        ScriptedModel(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            if (calls.incrementAndGet() == 1) {
                return Flux.just(ChatResponse.builder()
                        .id("resp-1")
                        .content(List.of(ToolUseBlock.builder()
                                .id("call-1")
                                .name("echo")
                                .input(Map.of("text", "hi"))
                                .build()))
                        .finishReason("tool_calls")
                        .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                        .build());
            }
            return Flux.just(ChatResponse.builder()
                    .id("resp-2")
                    .content(List.of(TextBlock.builder().text("done").build()))
                    .finishReason("stop")
                    .usage(ChatUsage.builder().inputTokens(20).outputTokens(5).build())
                    .build());
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }
}
