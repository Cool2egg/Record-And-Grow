package com.recordandgrow.agent.orchestration;

import com.recordandgrow.agent.dto.ChatAnswerResponse;
import com.recordandgrow.agent.dto.ChatAskRequest;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.config.AgentProperties;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.graph.GraphRetriever;
import com.recordandgrow.memory.Conversation;
import com.recordandgrow.memory.ConversationHistory;
import com.recordandgrow.memory.ConversationService;
import com.recordandgrow.memory.ConversationSummarizer;
import com.recordandgrow.memory.MemoryExtractor;
import com.recordandgrow.memory.MemoryService;
import com.recordandgrow.memory.MessageRole;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.retrieval.TimelineRetriever;
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import com.recordandgrow.retrieval.query.TimeBasis;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.retrieval.query.TimeWindowParser;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排链路（非流式）的端到端单测：真实 ReActAgent + 真实工具注册，
 * 只把模型与检索服务换成 mock，验证「工具调用 → 证据收集 → 回答 → 落库」整条链。
 */
class AgentChatServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T02:00:00Z"), ZONE);

    @Test
    void askShouldUseTimelineToolThenAnswerWithCitation() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setResearchTimeout(java.time.Duration.ofSeconds(5));
        agentProperties.setAnswerTimeout(java.time.Duration.ofSeconds(5));
        ChatProperties chatProperties = new ChatProperties();
        AiProperties aiProperties = new AiProperties();
        aiProperties.getChat().setFallbackEnabled(false);

        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.isConfigured()).thenReturn(true);

        ConversationService conversations = mock(ConversationService.class);
        Conversation conversation = new Conversation();
        conversation.setId(7L);
        when(conversations.getOrCreate(any(), anyString())).thenReturn(conversation);
        when(conversations.history(7L)).thenReturn(new ConversationHistory(null, List.of()));

        TimeWindow window = new TimeWindow(
                OffsetDateTime.parse("2026-10-15T00:00:00+08:00"),
                OffsetDateTime.parse("2026-10-22T00:00:00+08:00"), TimeBasis.DEFAULT, false);
        TimeWindowParser windowParser = mock(TimeWindowParser.class);
        when(windowParser.parse(anyString())).thenReturn(window);
        KeywordExtractor keywordExtractor = mock(KeywordExtractor.class);
        when(keywordExtractor.extract(anyString())).thenReturn(List.of());

        TimelineRetriever timelineRetriever = mock(TimelineRetriever.class);
        TimelineEntryResponse entry = new TimelineEntryResponse();
        entry.setId(11L);
        entry.setContent("早上跑步 5 公里");
        entry.setStartTime(OffsetDateTime.parse("2026-10-20T07:00:00+08:00"));
        when(timelineRetriever.retrieve(any(), any()))
                .thenReturn(new RetrievalResult(List.of(entry), window, 1, 1, false, List.of(), false));

        DocumentFusionService documents = mock(DocumentFusionService.class);
        MemoryService memories = mock(MemoryService.class);
        GraphRetriever graph = mock(GraphRetriever.class);
        ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        RoutingModel model = new RoutingModel();
        AgentChatService service = new AgentChatService(agentProperties, chatProperties,
                aiProperties, chatClient, model, model, conversations, summarizer, extractor,
                memories, timelineRetriever, documents, graph, windowParser, keywordExtractor,
                executor, CLOCK, ZONE);

        ChatAnswerResponse response = service.ask(new ChatAskRequest("我上周跑步了吗？", null, null, null));

        assertThat(response.answer()).contains("T1").contains("跑");
        assertThat(response.sources()).singleElement()
                .satisfies(source -> {
                    assertThat(source.ref()).isEqualTo("T1");
                    assertThat(source.type()).isEqualTo("timeline");
                    assertThat(source.id()).isEqualTo(11L);
                });
        assertThat(response.retrieval().included()).isEqualTo(1);
        assertThat(response.retrieval().totalIncluded()).isEqualTo(1);
        assertThat(response.conversationId()).isEqualTo(7L);
        assertThat(model.researchCalls()).isEqualTo(2);
        verify(conversations).append(eq(7L), eq(MessageRole.USER), eq("我上周跑步了吗？"), eq(null));
        verify(conversations).append(eq(7L), eq(MessageRole.ASSISTANT), anyString(), anyString());
        verify(extractor).extractAsync(eq(7L), anyString(), anyString());
        executor.shutdownNow();
    }

    /** 前两次模型调用属于检索 Agent（工具调用 + 收尾），第三次属于回答 Agent。 */
    static class RoutingModel implements Model {

        private final AtomicInteger calls = new AtomicInteger();

        int researchCalls() {
            return Math.min(calls.get(), 2);
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return Flux.just(ChatResponse.builder()
                        .id("research-1")
                        .content(List.of(ToolUseBlock.builder()
                                .id("call-1")
                                .name("search_timeline")
                                .input(Map.of("query", "上周跑步"))
                                .content("{\"query\":\"上周跑步\"}")
                                .build()))
                        .finishReason("tool_calls")
                        .usage(ChatUsage.builder().inputTokens(100).outputTokens(20).build())
                        .build());
            }
            if (call == 2) {
                return Flux.just(ChatResponse.builder()
                        .id("research-2")
                        .content(List.of(TextBlock.builder().text("找到一条跑步记录。").build()))
                        .finishReason("stop")
                        .usage(ChatUsage.builder().inputTokens(150).outputTokens(30).build())
                        .build());
            }
            return Flux.just(ChatResponse.builder()
                    .id("answer-1")
                    .content(List.of(TextBlock.builder()
                            .text("你上周跑过步：10 月 20 日早上跑了 5 公里 [T1]。")
                            .build()))
                    .finishReason("stop")
                    .usage(ChatUsage.builder().inputTokens(300).outputTokens(40).build())
                    .build());
        }

        @Override
        public String getModelName() {
            return "routing-model";
        }
    }
}
