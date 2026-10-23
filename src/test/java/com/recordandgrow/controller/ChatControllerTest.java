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
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.document.DocumentRetriever;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import com.recordandgrow.retrieval.query.TimeWindowParser;
import com.recordandgrow.timeline.TimelineEntryService;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T04:30:00Z"), ZONE);

    private FakeChatClient chatClient;
    private TimelineEntryService timelineEntryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatClient = new FakeChatClient();
        timelineEntryService = mock(TimelineEntryService.class);

        ChatProperties properties = new ChatProperties();
        TimelineRetriever retriever = new TimelineRetriever(timelineEntryService);
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        when(documentRetriever.retrieve(any())).thenReturn(
                com.recordandgrow.retrieval.document.DocumentRetrievalResult.notSearched());
        ChatService chatService = new ChatService(
                chatClient,
                new ChatSseService(chatClient, new AiProperties()),
                new TimeWindowParser(properties, CLOCK, ZONE),
                new KeywordExtractor(),
                new QueryRouter(chatClient, new RoutingProperties(), Runnable::run),
                new RetrievalEngine(retriever,
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
    }

    @Test
    void missingKeyReturns503BeforeTouchingDatabase() throws Exception {
        chatClient.configured = false;

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我昨天做了什么？\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50301));

        verifyNoInteractions(timelineEntryService);
    }

    @Test
    void missingKeyOnStreamReturns503WithoutSse() throws Exception {
        chatClient.configured = false;

        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我昨天做了什么？\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50301))
                .andExpect(request().asyncNotStarted());

        verifyNoInteractions(timelineEntryService);
    }

    @Test
    void databaseFailureReturns503WithoutStartingSse() throws Exception {
        when(timelineEntryService.search(any(), any(), anyList(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我昨天做了什么？\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50300))
                .andExpect(request().asyncNotStarted());
    }

    @Test
    void noHitStreamsEmptySourcesBeforeDelta() throws Exception {
        when(timelineEntryService.search(any(), any(), anyList(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 1, 100));
        chatClient.chunks = List.of(
                new ChatChunk("没有", null, null),
                new ChatChunk("找到记录", FinishReason.STOP, new ChatUsage(10, 4, 14)));

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我昨天做了什么？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:sources")))
                .andExpect(content().string(containsString("\"items\":[]")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")))
                .andReturn();

        String body = dispatched.getResponse().getContentAsString();
        assertThat(body.indexOf("event:start")).isLessThan(body.indexOf("event:sources"));
        assertThat(body.indexOf("event:sources")).isLessThan(body.indexOf("event:delta"));
    }

    @Test
    void hitStreamsSourcesThatMatchPromptRefs() throws Exception {
        TimelineEntryResponse entry = new TimelineEntryResponse();
        entry.setId(12L);
        entry.setContent("学习 SSE 的来源标注");
        entry.setStartTime(OffsetDateTime.parse("2026-10-18T20:00:00+08:00"));
        when(timelineEntryService.search(any(), any(), anyList(), anyInt()))
                .thenReturn(PageResult.of(List.of(entry), 1, 1, 100));
        chatClient.chunks = List.of(new ChatChunk("上周你在学习 SSE [T1]。",
                FinishReason.STOP, new ChatUsage(10, 8, 18)));

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我上周做了什么？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"ref\":\"T1\"")))
                .andExpect(content().string(containsString("\"included\":1")))
                .andExpect(content().string(containsString("\"type\":\"timeline\"")));

        String prompt = chatClient.lastRequest.messages().get(1).content();
        assertThat(prompt).contains("[T1] 2026-10-18 20:00 ~ 进行中");
    }

    @Test
    void blankQuestionIsRejectedBeforeRetrieval() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(timelineEntryService);
    }

    @Test
    void defaultMaxTokensComesFromConfig() throws Exception {
        when(timelineEntryService.search(any(), any(), anyList(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 1, 100));
        chatClient.response = new ChatResponse("ok", FinishReason.STOP, new ChatUsage(1, 1, 2));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我最近做了什么？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("ok"));

        assertThat(chatClient.lastRequest.maxTokens()).isEqualTo(1024);
        assertThat(chatClient.lastRequest.temperature()).isEqualTo(0.3);
    }

    private static final class FakeChatClient implements ChatClient {

        private boolean configured = true;
        private List<ChatChunk> chunks = List.of();
        private ChatResponse response = new ChatResponse("ok", FinishReason.STOP, new ChatUsage(1, 1, 2));
        private ChatRequest lastRequest;

        @Override
        public boolean isConfigured() {
            return configured;
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
