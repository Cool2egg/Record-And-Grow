package com.recordandgrow.agent;

import com.recordandgrow.agent.dto.ChatAnswerResponse;
import com.recordandgrow.agent.dto.ChatAskRequest;
import com.recordandgrow.agent.orchestration.AgentChatService;
import com.recordandgrow.agent.route.QueryRoute;
import com.recordandgrow.agent.route.QueryRouter;
import com.recordandgrow.agent.route.RouteDecision;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.sse.ChatSseService;
import com.recordandgrow.ai.sse.SseEvents;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.retrieval.RetrievalBundle;
import com.recordandgrow.retrieval.RetrievalEngine;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.retrieval.query.TimeWindowParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 最小闭环的编排：预检 → 解析时间窗 → 时间线检索 → 文档检索 → 组装 Prompt → 生成。
 *
 * <p>本模块单轮无状态，不加载历史消息。两路检索都发生在返回 {@link SseEmitter} 之前，
 * 因此无 key / DB 故障都能走正常的 HTTP 错误语义。
 *
 * <p>两路**并行**（时间线 + 文档，见 {@link RetrievalEngine}），失败语义与串行实现一致：
 * 两路同时失败时抛时间线的异常，异常类型不包装，全局异常处理器照旧映射。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatSseService chatSseService;
    private final TimeWindowParser timeWindowParser;
    private final KeywordExtractor keywordExtractor;
    private final QueryRouter queryRouter;
    private final RetrievalEngine retrievalEngine;
    private final ContextAssembler contextAssembler;
    private final ChatProperties properties;
    private final Clock clock;

    /**
     * AgentScope 编排（模块十一）。用 setter 注入而不是构造参数：
     * 保留模块四的构造签名，旧单测不需要跟着改；没引 Agent 时也能独立构造。
     */
    private AgentChatService agentChatService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAgentChatService(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    public ChatService(ChatClient chatClient,
                       ChatSseService chatSseService,
                       TimeWindowParser timeWindowParser,
                       KeywordExtractor keywordExtractor,
                       QueryRouter queryRouter,
                       RetrievalEngine retrievalEngine,
                       ContextAssembler contextAssembler,
                       ChatProperties properties,
                       Clock clock) {
        this.chatClient = chatClient;
        this.chatSseService = chatSseService;
        this.timeWindowParser = timeWindowParser;
        this.keywordExtractor = keywordExtractor;
        this.queryRouter = queryRouter;
        this.retrievalEngine = retrievalEngine;
        this.contextAssembler = contextAssembler;
        this.properties = properties;
        this.clock = clock;
    }

    public SseEmitter stream(ChatAskRequest request) {
        if (useAgent()) {
            return agentChatService.stream(request);
        }
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            PreparedChat prepared = prepare(request, requestId);
            return chatSseService.stream(prepared.chatRequest(), requestId, prepared.bundle().sources());
        }
    }

    public ChatAnswerResponse ask(ChatAskRequest request) {
        if (useAgent()) {
            return agentChatService.ask(request);
        }
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            PreparedChat prepared = prepare(request, requestId);
            ChatResponse response = chatClient.complete(prepared.chatRequest());
            ChatUsage usage = response.usage() == null ? ChatUsage.empty() : response.usage();
            log.info("chat completed: requestId={}, finishReason={}, promptTokens={}, "
                            + "completionTokens={}, totalTokens={}",
                    requestId,
                    response.finishReason() == null ? "UNKNOWN" : response.finishReason().name(),
                    usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
            return new ChatAnswerResponse(
                    requestId,
                    response.content(),
                    prepared.bundle().sources().items(),
                    prepared.bundle().sources().retrieval(),
                    response.finishReason() == null ? "UNKNOWN" : response.finishReason().name(),
                    usage);
        }
    }

    private PreparedChat prepare(ChatAskRequest request, String requestId) {        // 1. 预检：没配 key 不查库、不建流
        if (!chatClient.isConfigured()) {
            throw new AiException(AiErrorType.NOT_CONFIGURED, "AI 服务未配置");
        }

        String question = request.question().trim();
        if (question.length() > properties.getMaxQuestionChars()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "问题过长，最多 " + properties.getMaxQuestionChars() + " 个字符");
        }

        // 2. 时间窗与关键词
        TimeWindow window = timeWindowParser.parse(question);
        List<String> keywords = keywordExtractor.extract(question);
        OffsetDateTime now = OffsetDateTime.now(clock);

        // 3/4. 按路由并行召回（降级不抛；SQL 异常按模块四原则上抛）
        RouteDecision routeDecision = queryRouter.decide(question);
        RetrievalBundle retrieved = retrievalEngine.retrieve(
                question, window, keywords, routeDecision.route());
        RetrievalResult retrieval = retrieved.timeline();
        DocumentRetrievalResult documents = retrieved.documents();
        long retrievalMs = retrieved.timelineMs();
        long documentRetrievalMs = retrieved.documentMs();

        // 5. 组装 Prompt 与 sources；DIRECT 不检索，用最小上下文
        ContextBundle bundle = routeDecision.route() == QueryRoute.DIRECT
                ? contextAssembler.assembleDirect(question, now)
                : contextAssembler.assemble(retrieval, documents, question, now);

        // 6. 请求参数
        int cap = Math.max(64, properties.getMaxAnswerTokens());
        int maxTokens = request.maxTokens() == null
                ? Math.min(properties.getAnswerMaxTokens(), cap)
                : Math.min(request.maxTokens(), cap);
        maxTokens = Math.max(64, maxTokens);
        double temperature = request.temperature() == null
                ? properties.getTemperature()
                : request.temperature();

        ChatRequest chatRequest = new ChatRequest(
                bundle.messages(),
                maxTokens,
                temperature);

        SseEvents.RetrievalMeta meta = bundle.sources().retrieval();
        log.info("chat prepared: requestId={}, questionChars={}, timeBasis={}, clamped={}, "
                        + "keywords={}, matched={}, retrieved={}, included={}, dropped={}, limitTruncated={}, "
                        + "budgetTruncated={}, keywordFallback={}, promptChars={}, retrievalMs={}, "
                        + "documentSearched={}, documentsRetrieved={}, documentIncluded={}, "
                        + "documentsDegraded={}, degradeReason={}, queryEmbeddingMs={}, documentSqlMs={}, "
                        + "documentRetrievalMs={}, topScore={}, documentsBudgetTruncated={}, "
                        + "route={}, routeFallback={}",
                requestId,
                question.length(),
                window.basis(),
                window.clamped(),
                keywords.size(),
                retrieval.matched(),
                retrieval.retrieved(),
                bundle.includedCount(),
                bundle.droppedCount(),
                retrieval.limitTruncated(),
                bundle.budgetTruncated(),
                retrieval.keywordFallback(),
                bundle.promptChars(),
                retrievalMs,
                meta.documentSearched(),
                meta.documentsRetrieved(),
                meta.documentIncluded(),
                meta.documentsDegraded(),
                meta.degradeReason(),
                documents.queryEmbeddingMs(),
                documents.documentSqlMs(),
                documentRetrievalMs,
                formatScore(documents.topScore()),
                bundle.documentsBudgetTruncated(),
                routeDecision.route(),
                routeDecision.fallback());

        if (routeDecision.fallback()) {
            // 兜底必须可见：路由失败不是错误，但会改变这次检索的范围
            log.warn("routing fallback: reason={}, requestId={}", routeDecision.reason(), requestId);
        }

        if (documents.degraded()) {
            // 降级必须可见；只记原因与 requestId，不记问题原文
            log.warn("documents degraded: reason={}, requestId={}",
                    documents.degradeReason(), requestId);
        }

        return new PreparedChat(chatRequest, bundle);
    }

    private boolean useAgent() {
        return agentChatService != null && agentChatService.isEnabled();
    }

    /** 日志用：4 位小数；空结果为 {@code null}。 */    private static String formatScore(Double score) {
        return score == null ? null : String.format(Locale.ROOT, "%.4f", score);
    }

    private record PreparedChat(ChatRequest chatRequest, ContextBundle bundle) {
    }
}
