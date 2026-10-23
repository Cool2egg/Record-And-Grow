package com.recordandgrow.controller;

import com.recordandgrow.agent.ChatService;
import com.recordandgrow.agent.ContextAssembler;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatStream;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.FinishReason;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.ai.sse.ChatSseService;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.infra.exception.GlobalExceptionHandler;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.agent.route.QueryRouter;
import com.recordandgrow.config.FusionProperties;
import com.recordandgrow.config.RerankProperties;
import com.recordandgrow.config.RoutingProperties;
import com.recordandgrow.retrieval.RetrievalEngine;
import com.recordandgrow.retrieval.TimelineRetriever;
import com.recordandgrow.retrieval.document.DocumentDegradeReason;
import com.recordandgrow.retrieval.document.DocumentHit;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.document.DocumentRetriever;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import com.recordandgrow.retrieval.query.TimeWindowParser;
import com.recordandgrow.timeline.TimelineEntryService;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双路接入的 MockMvc 测试：sources 里的 {@code type=document}、T 前 D 后、
 * SSE 事件顺序不变、降级 200 + 可见标记、无文档时与模块四一致。
 *
 * <p>用 mock 的 {@link DocumentRetriever} + fake LLM，不起 Spring 上下文、不碰数据库。
 */
class ChatControllerDocumentTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T04:30:00Z"), ZONE);

    private FakeChatClient chatClient;
    private TimelineEntryService timelineEntryService;
    private DocumentRetriever documentRetriever;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatClient = new FakeChatClient();
        timelineEntryService = mock(TimelineEntryService.class);
        documentRetriever = mock(DocumentRetriever.class);

        ChatProperties properties = new ChatProperties();
        ChatService chatService = new ChatService(
                chatClient,
                new ChatSseService(chatClient, new AiProperties()),
                new TimeWindowParser(properties, CLOCK, ZONE),
                new KeywordExtractor(),
                new QueryRouter(chatClient, new RoutingProperties(), Runnable::run),
                new RetrievalEngine(new TimelineRetriever(timelineEntryService),
                        new DocumentFusionService(documentRetriever,
                                mock(org.springframework.beans.factory.ObjectProvider.class),
                                mock(org.springframework.beans.factory.ObjectProvider.class),
                                new FusionProperties(), new RerankProperties(), properties),
                        Runnable::run),
                new ContextAssembler(properties, ZONE),
                properties,
                CLOCK);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        stubTimeline();
    }

    @Test
    void streamEmitsDocumentSourcesBetweenSourcesAndDelta() throws Exception {
        when(documentRetriever.retrieve(any())).thenReturn(DocumentRetrievalResult.of(List.of(
                new DocumentHit(57L, 3L, "Docker 实践笔记", "部署 > 镜像加速",
                        "配置 daemon.json 的 registry-mirrors 字段后重启 docker。", 0.812345678, 0, 30)),
                0.812345678));

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Docker 镜像加速怎么配？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:sources")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("\"type\":\"document\"")))
                .andExpect(content().string(containsString("\"chunkId\":57")))
                .andExpect(content().string(containsString("\"title\":\"Docker 实践笔记\"")))
                .andExpect(content().string(containsString("\"documentSearched\":true")))
                .andExpect(content().string(containsString("\"documentIncluded\":1")))
                .andExpect(content().string(containsString("\"totalIncluded\":2")))
                .andReturn();

        String body = dispatched.getResponse().getContentAsString();
        assertThat(body.indexOf("event:start")).isLessThan(body.indexOf("event:sources"));
        assertThat(body.indexOf("event:sources")).isLessThan(body.indexOf("event:delta"));
        assertThat(count(body, "event:sources")).as("sources 只发一次").isEqualTo(1);
        assertThat(body.indexOf("\"ref\":\"T1\"")).isLessThan(body.indexOf("\"ref\":\"D1\""));
    }

    @Test
    void nonStreamResponseExposesDocumentSourceAndCounts() throws Exception {
        when(documentRetriever.retrieve(any())).thenReturn(DocumentRetrievalResult.of(List.of(
                new DocumentHit(57L, 3L, "Docker 实践笔记", "部署", "正文", 0.8, 0, 2)), 0.8));
        chatClient.response = new ChatResponse("资料里提到 [D1]。", FinishReason.STOP,
                new ChatUsage(1, 1, 2));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Docker 镜像加速怎么配？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[1].type").value("document"))
                .andExpect(jsonPath("$.data.sources[1].id").value(3))
                .andExpect(jsonPath("$.data.sources[1].chunkId").value(57))
                .andExpect(jsonPath("$.data.retrieval.documentSearched").value(true))
                .andExpect(jsonPath("$.data.retrieval.documentsRetrieved").value(1))
                .andExpect(jsonPath("$.data.retrieval.documentIncluded").value(1))
                .andExpect(jsonPath("$.data.retrieval.totalIncluded").value(2))
                .andExpect(jsonPath("$.data.retrieval.documentsDegraded").value(false));
    }

    @Test
    void degradedStillReturns200WithVisibleMarker() throws Exception {
        when(documentRetriever.retrieve(any()))
                .thenReturn(DocumentRetrievalResult.degraded(DocumentDegradeReason.TIMEOUT));
        chatClient.response = new ChatResponse("没有找到记录", FinishReason.STOP, new ChatUsage(1, 1, 2));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我上周做了什么？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval.documentsDegraded").value(true))
                .andExpect(jsonPath("$.data.retrieval.documentSearched").value(false))
                .andExpect(jsonPath("$.data.retrieval.degradeReason").value("TIMEOUT"))
                .andExpect(jsonPath("$.data.retrieval.documentIncluded").value(0))
                .andExpect(jsonPath("$.data.retrieval.totalIncluded").value(1));

        String prompt = chatClient.lastRequest.messages().get(1).content();
        assertThat(prompt).contains("【文档检索】本次不可用");
        assertThat(chatClient.lastRequest.messages().get(0).content())
                .as("降级时 system 仍是模块四原文").doesNotContain("两类材料");
    }

    @Test
    void noDocumentsKeepsModuleFourShape() throws Exception {
        when(documentRetriever.retrieve(any())).thenReturn(DocumentRetrievalResult.notSearched());
        chatClient.response = new ChatResponse("上周你在学习 SSE [T1]。", FinishReason.STOP,
                new ChatUsage(1, 1, 2));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我上周做了什么？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources.length()").value(1))
                .andExpect(jsonPath("$.data.sources[0].type").value("timeline"))
                .andExpect(jsonPath("$.data.retrieval.documentSearched").value(false))
                .andExpect(jsonPath("$.data.retrieval.documentsDegraded").value(false))
                .andExpect(jsonPath("$.data.retrieval.totalIncluded").value(1));

        String system = chatClient.lastRequest.messages().get(0).content();
        String prompt = chatClient.lastRequest.messages().get(1).content();
        assertThat(system).as("无文档时 system 用模块四原文").doesNotContain("两类材料");
        assertThat(prompt).doesNotContain("【文档片段】").doesNotContain("【文档检索】本次不可用");
    }

    @Test
    void zeroDocumentHitsStillReportSearched() throws Exception {
        when(documentRetriever.retrieve(any()))
                .thenReturn(DocumentRetrievalResult.of(List.of(), 0.12));
        chatClient.response = new ChatResponse("ok", FinishReason.STOP, new ChatUsage(1, 1, 2));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我上周做了什么？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval.documentSearched").value(true))
                .andExpect(jsonPath("$.data.retrieval.documentsRetrieved").value(0))
                .andExpect(jsonPath("$.data.sources.length()").value(1));
    }

    private void stubTimeline() {
        TimelineEntryResponse entry = new TimelineEntryResponse();
        entry.setId(12L);
        entry.setContent("学习 SSE 的来源标注");
        entry.setStartTime(OffsetDateTime.parse("2026-10-18T20:00:00+08:00"));
        when(timelineEntryService.search(any(), any(), anyList(), anyInt()))
                .thenReturn(PageResult.of(List.of(entry), 1, 1, 100));
        chatClient.chunks = List.of(new ChatChunk("上周你在学习 SSE [T1]。",
                FinishReason.STOP, new ChatUsage(10, 8, 18)));
    }

    private static int count(String text, String token) {
        int total = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            total++;
            index = text.indexOf(token, index + token.length());
        }
        return total;
    }

    private static final class FakeChatClient implements ChatClient {

        private List<ChatChunk> chunks = List.of();
        private ChatResponse response = new ChatResponse("ok", FinishReason.STOP, new ChatUsage(1, 1, 2));
        private ChatRequest lastRequest;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            lastRequest = request;
            return response;
        }

        @Override
        public ChatStream stream(ChatRequest request) {
            lastRequest = request;
            return new FakeStream(chunks);
        }
    }

    private static final class FakeStream implements ChatStream {

        private final Deque<ChatChunk> chunks = new ArrayDeque<>();

        private FakeStream(List<ChatChunk> chunks) {
            this.chunks.addAll(chunks);
        }

        @Override
        public boolean hasNext() {
            return !chunks.isEmpty();
        }

        @Override
        public ChatChunk next() {
            return chunks.poll();
        }

        @Override
        public void close() {
        }
    }
}
