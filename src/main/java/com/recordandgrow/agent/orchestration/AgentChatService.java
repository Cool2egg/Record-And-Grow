package com.recordandgrow.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recordandgrow.agent.dto.ChatAnswerResponse;
import com.recordandgrow.agent.dto.ChatAskRequest;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.ai.sse.SseEvents;
import com.recordandgrow.config.AgentProperties;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.graph.GraphRetriever;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.memory.Conversation;
import com.recordandgrow.memory.ConversationHistory;
import com.recordandgrow.memory.ConversationService;
import com.recordandgrow.memory.ConversationSummarizer;
import com.recordandgrow.memory.MemoryExtractor;
import com.recordandgrow.memory.MessageRole;
import com.recordandgrow.memory.MemoryService;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.retrieval.TimelineRetriever;
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.retrieval.query.TimeWindowParser;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope 编排入口（模块十一）。
 *
 * <p>两段式：检索 Agent（ReAct + 工具）先收集证据，回答 Agent（无工具）再基于证据流式作答。
 * 这样 SSE 的顺序仍是 {@code start → sources → delta… → done}，前端契约不变。
 *
 * <p>失败语义：检索阶段失败会回退到固定双路检索，回答阶段失败发 {@code error} 事件；
 * 任何阶段都不会把内部异常文本直接透给客户端。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            // 与 SSE / HTTP 响应保持一致：时间写 ISO-8601，而不是 epoch 秒。
            // 历史消息里的 sources 会被前端回放，数字时间会让边注跳到 1970 年。
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ScheduledExecutorService HEARTBEAT =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "agent-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final AgentProperties properties;
    private final ChatProperties chatProperties;
    private final AiProperties aiProperties;
    private final ChatClient chatClient;
    private final Model model;
    private final Model fallbackModel;
    private final ConversationService conversationService;
    private final ConversationSummarizer summarizer;
    private final MemoryExtractor memoryExtractor;
    private final MemoryService memoryService;
    private final TimelineRetriever timelineRetriever;
    private final DocumentFusionService documentFusionService;
    private final GraphRetriever graphRetriever;
    private final TimeWindowParser windowParser;
    private final com.recordandgrow.retrieval.query.KeywordExtractor keywordExtractor;
    private final ExecutorService executor;
    private final Clock clock;
    private final ZoneId zone;

    /** 技能注册表可选：没有 skills 目录也不影响对话。 */
    private com.recordandgrow.skill.SkillRegistry skillRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSkillRegistry(com.recordandgrow.skill.SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public AgentChatService(AgentProperties properties,
                            ChatProperties chatProperties,
                            AiProperties aiProperties,
                            ChatClient chatClient,
                            @Qualifier("agentModel") Model model,
                            @Qualifier("agentFallbackModel") Model fallbackModel,
                            ConversationService conversationService,
                            ConversationSummarizer summarizer,
                            MemoryExtractor memoryExtractor,
                            MemoryService memoryService,
                            TimelineRetriever timelineRetriever,
                            DocumentFusionService documentFusionService,
                            GraphRetriever graphRetriever,
                            TimeWindowParser windowParser,
                            com.recordandgrow.retrieval.query.KeywordExtractor keywordExtractor,
                            @Qualifier("agentExecutor") ExecutorService executor,
                            Clock clock,
                            ZoneId zone) {
        this.properties = properties;
        this.chatProperties = chatProperties;
        this.aiProperties = aiProperties;
        this.chatClient = chatClient;
        this.model = model;
        this.fallbackModel = fallbackModel;
        this.conversationService = conversationService;
        this.summarizer = summarizer;
        this.memoryExtractor = memoryExtractor;
        this.memoryService = memoryService;
        this.timelineRetriever = timelineRetriever;
        this.documentFusionService = documentFusionService;
        this.graphRetriever = graphRetriever;
        this.windowParser = windowParser;
        this.keywordExtractor = keywordExtractor;
        this.executor = executor;
        this.clock = clock;
        this.zone = zone;
    }

    /** 有 key 且开关打开才走 Agent；否则由 {@code ChatService} 回落到模块四链路。 */
    public boolean isEnabled() {
        return properties.isEnabled() && chatClient.isConfigured();
    }

    public SseEmitter stream(ChatAskRequest request) {
        String requestId = UUID.randomUUID().toString();
        String question = validate(request);
        AgentRun run = newRun(request, question);
        AgentSseSession session = new AgentSseSession(requestId,
                buildEmitter(), run);
        // 检索阶段的工具调用一边跑一边把 step 写进 SSE；sources 仍固定在第一个 delta 前
        run.context.setStepSink(session::sendStep);
        session.startHeartbeat();
        if (!session.sendStart()) {
            session.complete();
            return session.emitter();
        }
        executor.execute(() -> {
            MDC.put("requestId", requestId);
            try {
                executeStream(run, session);
            } finally {
                MDC.remove("requestId");
            }
        });
        return session.emitter();
    }

    public ChatAnswerResponse ask(ChatAskRequest request) {
        String requestId = UUID.randomUUID().toString();
        String question = validate(request);
        AgentRun run = newRun(request, question);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            String notes = research(run);
            run.context.setResearchNotes(notes);
            AnswerResult result = answerBlocking(run);
            persistAssistant(run, result.answer());
            afterAnswer(run, result.answer());
            SseEvents.Sources sources = run.context.sources();
            return new ChatAnswerResponse(requestId, result.answer(), sources.items(),
                    sources.retrieval(), result.finishReason(), result.usage(), run.conversationId);
        }
    }

    // ---- 执行 ----

    private void executeStream(AgentRun run, AgentSseSession session) {
        try {
            String notes = research(run);
            run.context.setResearchNotes(notes);
            if (session.isCancelled()) {
                return;
            }
            if (!session.sendSources(run.context.sources())) {
                return;
            }
            AnswerResult result = answerStreaming(run, session);
            if (result == null || session.isCancelled()) {
                return;
            }
            if (!session.sendDone(result.finishReason(), result.usage())) {
                return;
            }
            persistAssistant(run, result.answer());
            session.complete();
            log.info("agent chat done: requestId={}, conversationId={}, evidence={}, tools={}, "
                            + "answerChars={}, promptTokens={}, completionTokens={}",
                    session.requestId(), run.conversationId, run.context.evidenceCount(),
                    run.context.toolCalls(), result.answer().length(),
                    result.usage().promptTokens(), result.usage().completionTokens());
            afterAnswer(run, result.answer());
        } catch (AiException e) {
            log.warn("agent chat failed: requestId={}, type={}", session.requestId(), e.getType());
            session.fail(errorCode(e.getType()), e.getType().name(), messageFor(e.getType()));
        } catch (RuntimeException e) {
            log.warn("agent chat failed: requestId={}", session.requestId(), e);
            session.fail(ErrorCode.AI_UPSTREAM_ERROR.getCode(), AiErrorType.UPSTREAM_ERROR.name(),
                    "AI 服务暂时不可用");
        }
    }

    private String research(AgentRun run) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChatAgentTools(run.context, timelineRetriever,
                documentFusionService, memoryService, graphRetriever, windowParser,
                keywordExtractor, chatProperties, properties, clock, zone));

        ReActAgent agent = agentBuilder("researcher", AgentPrompts.RESEARCH_SYSTEM, toolkit,
                properties.getResearchMaxIters())
                .generateOptions(options(0.2, 800))
                .build();
        try {
            String input = AgentPrompts.researchInput(run.summary, run.history,
                    run.question, 2000);
            Msg result = agent.call(input, RuntimeContext.empty())
                    .block(properties.getResearchTimeout());
            String text = result == null ? "" : result.getTextContent();
            log.info("agent research done: conversationId={}, tools={}, notesChars={}",
                    run.conversationId, run.context.toolCalls(), text == null ? 0 : text.length());
            return truncate(text, properties.getMaxResearchNotesChars());
        } catch (RuntimeException e) {
            log.warn("agent research failed, fallback to fixed retrieval: conversationId={}, type={}",
                    run.conversationId, e.getClass().getSimpleName());
            fallbackRetrieval(run);
            return "检索 Agent 不可用，已回退到固定双路检索。";
        } finally {
            agent.close();
        }
    }

    /** 检索 Agent 失败时的兜底：走模块四/六的固定两路，至少保证回答有材料。 */
    private void fallbackRetrieval(AgentRun run) {
        try {
            RetrievalResult timeline = timelineRetriever.retrieve(run.window, run.keywords);
            run.context.addTimeline(timeline);
        } catch (RuntimeException e) {
            log.warn("fallback timeline retrieval failed: type={}", e.getClass().getSimpleName());
        }
        try {
            DocumentRetrievalResult documents = documentFusionService.retrieve(run.question);
            run.context.addDocuments(documents);
        } catch (RuntimeException e) {
            log.warn("fallback document retrieval failed: type={}", e.getClass().getSimpleName());
        }
    }

    private AnswerResult answerStreaming(AgentRun run, AgentSseSession session) {
        List<Msg> messages = answerMessages(run);
        ReActAgent agent = agentBuilder("answerer", AgentPrompts.ANSWER_SYSTEM, new Toolkit(), 1)
                .generateOptions(options(chatProperties.getTemperature(),
                        answerMaxTokens(run)))
                .build();

        StringBuilder answer = new StringBuilder();
        AtomicReference<Msg> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Disposable disposable = agent.streamEvents(messages, RuntimeContext.empty())
                .timeout(properties.getAnswerTimeout())
                .subscribe(event -> {
                    if (event instanceof TextBlockDeltaEvent delta) {
                        String text = delta.getDelta();
                        if (text != null && !text.isEmpty()) {
                            answer.append(text);
                            session.sendDelta(text);
                        }
                    } else if (event instanceof AgentResultEvent result) {
                        resultRef.set(result.getResult());
                    } else if (event instanceof ExceedMaxItersEvent) {
                        log.warn("agent answer exceeded max iters: conversationId={}", run.conversationId);
                    }
                }, error -> {
                    errorRef.set(error);
                    latch.countDown();
                }, latch::countDown);

        session.attach(disposable);
        try {
            if (!latch.await(properties.getAnswerTimeout().toMillis() + 2_000, TimeUnit.MILLISECONDS)) {
                disposable.dispose();
                throw new AiException(AiErrorType.TIMEOUT, "回答超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposable.dispose();
            throw new AiException(AiErrorType.STREAM_INTERRUPTED, "回答被中断");
        } finally {
            agent.close();
        }
        if (errorRef.get() != null) {
            throw asRuntime(errorRef.get());
        }
        Msg result = resultRef.get();
        if (result != null && answer.isEmpty() && result.getTextContent() != null) {
            answer.append(result.getTextContent());
        }
        if (answer.isEmpty()) {
            throw new AiException(AiErrorType.BAD_RESPONSE, "模型返回空回答");
        }
        ChatUsage usage = toUsage(result == null ? null : result.getUsage());
        logCitationIssues(run, answer.toString());
        return new AnswerResult(answer.toString(), "STOP", usage);
    }

    private AnswerResult answerBlocking(AgentRun run) {
        ReActAgent agent = agentBuilder("answerer", AgentPrompts.ANSWER_SYSTEM, new Toolkit(), 1)
                .generateOptions(options(chatProperties.getTemperature(), answerMaxTokens(run)))
                .build();
        try {
            Msg result = agent.call(answerMessages(run), RuntimeContext.empty())
                    .block(properties.getAnswerTimeout());
            String text = result == null ? "" : result.getTextContent();
            if (text == null || text.isBlank()) {
                throw new AiException(AiErrorType.BAD_RESPONSE, "模型返回空回答");
            }
            logCitationIssues(run, text);
            return new AnswerResult(text, "STOP", toUsage(result.getUsage()));
        } finally {
            agent.close();
        }
    }

    private ReActAgent.Builder agentBuilder(String name, String sysPrompt, Toolkit toolkit, int maxIters) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(Math.max(1, maxIters))
                .maxRetries(1);
        if (aiProperties.getChat().isFallbackEnabled()) {
            builder.fallbackModel(fallbackModel);
        }
        return builder;
    }

    private List<Msg> answerMessages(AgentRun run) {
        List<Msg> messages = new ArrayList<>();
        String summary = run.summary;
        if (summary != null && !summary.isBlank()) {
            messages.add(Msg.builder().role(MsgRole.SYSTEM)
                    .textContent("【会话摘要】\n" + truncate(summary, 800)).build());
        }
        for (ChatMessage message : run.history) {
            messages.add(Msg.builder()
                    .role(message.role() == ChatMessage.Role.USER ? MsgRole.USER : MsgRole.ASSISTANT)
                    .textContent(message.content() == null ? "" : message.content())
                    .build());
        }
        messages.add(userMsg(AgentPrompts.answerInput(run.context, summary, run.question,
                run.now, zone, properties.getMaxEvidenceChars(),
                skillRegistry == null ? List.of() : skillRegistry.match(run.question))));
        return messages;
    }

    private int answerMaxTokens(AgentRun run) {
        int cap = Math.max(64, chatProperties.getMaxAnswerTokens());
        if (properties.getAnswerMaxTokens() != null) {
            return Math.max(64, Math.min(properties.getAnswerMaxTokens(), cap));
        }
        int requested = run.requestedMaxTokens == null
                ? chatProperties.getAnswerMaxTokens() : run.requestedMaxTokens;
        return Math.max(64, Math.min(requested, cap));
    }

    private GenerateOptions options(double temperature, int maxTokens) {
        return GenerateOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                // DeepSeek 兼容端点：不显式关闭 thinking 时会消耗推理 token，
                // 表现为 1024 token 内正文被截断。与 ChatClient 的配置保持一致。
                .additionalBodyParam("thinking", java.util.Map.of("type",
                        aiProperties.getChat().thinkingEnabled() ? "enabled" : "disabled"))
                .build();
    }

    // ---- 会话 ----

    private AgentRun newRun(ChatAskRequest request, String question) {
        Conversation conversation = conversationService.getOrCreate(
                request.conversationId(), question);
        // 先取历史再写本轮问题，否则当前问题会重复一次
        ConversationHistory history = conversationService.history(conversation.getId());
        conversationService.append(conversation.getId(), MessageRole.USER, question, null);
        conversationService.ensureTitle(conversation.getId(), question);

        OffsetDateTime now = OffsetDateTime.now(clock);
        TimeWindow window = windowParser.parse(question);
        List<String> keywords = keywordExtractor.extract(question);
        AgentToolContext context = new AgentToolContext(conversation.getId(), window,
                keywords, properties.getMaxEvidenceItems(), zone);
        return new AgentRun(conversation.getId(), question, history.summary(),
                history.messages(), window, keywords, now, request.maxTokens(), context);
    }

    private void persistAssistant(AgentRun run, String answer) {
        try {
            String sourcesJson = JSON.writeValueAsString(run.context.sources());
            conversationService.append(run.conversationId, MessageRole.ASSISTANT, answer, sourcesJson);
        } catch (RuntimeException | IOException e) {
            // 落库失败不能回滚已经发给客户端的回答，只记日志
            log.warn("persist assistant message failed: conversationId={}, type={}",
                    run.conversationId, e.getClass().getSimpleName());
        }
    }

    private void afterAnswer(AgentRun run, String answer) {
        summarizer.summarizeAsync(run.conversationId);
        memoryExtractor.extractAsync(run.conversationId, run.question, answer);
    }

    private String validate(ChatAskRequest request) {
        if (!chatClient.isConfigured()) {
            throw new AiException(AiErrorType.NOT_CONFIGURED, "AI 服务未配置");
        }
        String question = request.question() == null ? "" : request.question().trim();
        if (question.length() > chatProperties.getMaxQuestionChars()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "问题过长，最多 " + chatProperties.getMaxQuestionChars() + " 个字符");
        }
        return question;
    }

    private SseEmitter buildEmitter() {
        long timeout = properties.getResearchTimeout()
                .plus(properties.getAnswerTimeout())
                .plusSeconds(30)
                .toMillis();
        return new SseEmitter(timeout);
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    /**
     * 只记日志、不改答案：编号幻觉（引用了本轮不存在的 G4/G5）没有安全的自动修法，
     * 先让它在日志里可见，后续再接评测或引用校验。
     */
    private void logCitationIssues(AgentRun run, String answer) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (EvidenceItem item : run.context.evidence()) {
            allowed.add(item.ref());
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[([TDMG])(\\d+)]").matcher(answer);
        java.util.List<String> invalid = new java.util.ArrayList<>();
        while (matcher.find()) {
            String ref = matcher.group(1) + matcher.group(2);
            if (!allowed.contains(ref)) {
                invalid.add(ref);
            }
        }
        if (!invalid.isEmpty()) {
            log.warn("agent answer has unknown citations: conversationId={}, invalid={}, allowed={}",
                    run.conversationId, invalid, allowed);
        }
    }

    private static ChatUsage toUsage(io.agentscope.core.model.ChatUsage usage) {
        if (usage == null) {
            return ChatUsage.empty();
        }
        return new ChatUsage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
    }

    private static int errorCode(AiErrorType type) {
        return switch (type) {
            case NOT_CONFIGURED -> ErrorCode.AI_UNAVAILABLE.getCode();
            case TIMEOUT, RATE_LIMITED -> ErrorCode.AI_UNAVAILABLE.getCode();
            default -> ErrorCode.AI_UPSTREAM_ERROR.getCode();
        };
    }

    private static String messageFor(AiErrorType type) {
        return switch (type) {
            case NOT_CONFIGURED -> "AI 服务未配置";
            case RATE_LIMITED -> "AI 服务请求过于频繁";
            case TIMEOUT -> "AI 服务响应超时";
            default -> "AI 服务暂时不可用";
        };
    }

    private static RuntimeException asRuntime(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(error);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record AgentRun(Long conversationId, String question, String summary,
                            List<ChatMessage> history, TimeWindow window, List<String> keywords,
                            OffsetDateTime now, Integer requestedMaxTokens,
                            AgentToolContext context) {
    }

    private record AnswerResult(String answer, String finishReason, ChatUsage usage) {
    }

    /**
     * SSE 会话：心跳、取消、串行写。写失败即视为客户端断开，取消后续所有发送。
     */
    static final class AgentSseSession {

        private final String requestId;
        private final SseEmitter emitter;
        private final AgentRun run;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Disposable> disposable = new AtomicReference<>();
        private ScheduledFuture<?> heartbeat;

        AgentSseSession(String requestId, SseEmitter emitter, AgentRun run) {
            this.requestId = requestId;
            this.emitter = emitter;
            this.run = run;
            emitter.onCompletion(() -> cancelled.set(true));
            emitter.onTimeout(() -> {
                cancelled.set(true);
                dispose();
            });
            emitter.onError(error -> {
                cancelled.set(true);
                dispose();
            });
        }

        String requestId() {
            return requestId;
        }

        SseEmitter emitter() {
            return emitter;
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void attach(Disposable value) {
            disposable.set(value);
            if (cancelled.get()) {
                value.dispose();
            }
        }

        void startHeartbeat() {
            heartbeat = HEARTBEAT.scheduleAtFixedRate(this::ping, 15, 15, TimeUnit.SECONDS);
        }

        private synchronized void ping() {
            if (cancelled.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                cancelled.set(true);
                dispose();
            }
        }

        boolean sendStart() {
            return send("start", new SseEvents.Start(requestId, run.conversationId));
        }

        boolean sendSources(SseEvents.Sources sources) {
            return send("sources", sources);
        }

        boolean sendStep(SseEvents.Step step) {
            return send("step", step);
        }

        boolean sendDelta(String content) {
            return send("delta", new SseEvents.Delta(content));
        }

        boolean sendDone(String finishReason, ChatUsage usage) {
            return send("done", new SseEvents.Done(finishReason, usage));
        }

        void fail(int code, String errorType, String message) {
            if (cancelled.get()) {
                return;
            }
            send("error", new SseEvents.Error(code, errorType, message, false, requestId));
            complete();
        }

        private synchronized boolean send(String name, Object data) {
            if (cancelled.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(name)
                        .data(data, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | RuntimeException e) {
                cancelled.set(true);
                dispose();
                return false;
            }
        }

        synchronized void complete() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            dispose();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 客户端已经断开
            }
        }

        private void dispose() {
            Disposable value = disposable.getAndSet(null);
            if (value != null) {
                value.dispose();
            }
        }
    }
}
