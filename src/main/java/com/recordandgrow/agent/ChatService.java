package com.recordandgrow.agent;

import com.recordandgrow.agent.dto.ChatAnswerResponse;
import com.recordandgrow.agent.dto.ChatAskRequest;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.sse.ChatSseService;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.retrieval.TimelineRetriever;
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
import java.util.UUID;

/**
 * 最小闭环的编排：预检 → 解析时间窗 → 检索 → 组装 Prompt → 生成。
 *
 * <p>本模块单轮无状态，不加载历史消息。检索发生在返回 {@link SseEmitter} 之前，
 * 因此无 key / DB 故障都能走正常的 HTTP 错误语义。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatSseService chatSseService;
    private final TimeWindowParser timeWindowParser;
    private final KeywordExtractor keywordExtractor;
    private final TimelineRetriever timelineRetriever;
    private final ContextAssembler contextAssembler;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatService(ChatClient chatClient,
                       ChatSseService chatSseService,
                       TimeWindowParser timeWindowParser,
                       KeywordExtractor keywordExtractor,
                       TimelineRetriever timelineRetriever,
                       ContextAssembler contextAssembler,
                       ChatProperties properties,
                       Clock clock) {
        this.chatClient = chatClient;
        this.chatSseService = chatSseService;
        this.timeWindowParser = timeWindowParser;
        this.keywordExtractor = keywordExtractor;
        this.timelineRetriever = timelineRetriever;
        this.contextAssembler = contextAssembler;
        this.properties = properties;
        this.clock = clock;
    }

    public SseEmitter stream(ChatAskRequest request) {
        String requestId = UUID.randomUUID().toString();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            PreparedChat prepared = prepare(request, requestId);
            return chatSseService.stream(prepared.chatRequest(), requestId, prepared.bundle().sources());
        }
    }

    public ChatAnswerResponse ask(ChatAskRequest request) {
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

    private PreparedChat prepare(ChatAskRequest request, String requestId) {
        // 1. 预检：没配 key 不查库、不建流
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

        // 3. 检索
        long started = System.nanoTime();
        RetrievalResult retrieval = timelineRetriever.retrieve(window, keywords);
        long retrievalMs = (System.nanoTime() - started) / 1_000_000;

        // 4. 组装 Prompt 与 sources
        ContextBundle bundle = contextAssembler.assemble(retrieval, question, now);

        // 5. 请求参数
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

        log.info("chat prepared: requestId={}, questionChars={}, timeBasis={}, clamped={}, "
                        + "keywords={}, matched={}, retrieved={}, included={}, dropped={}, limitTruncated={}, "
                        + "budgetTruncated={}, keywordFallback={}, promptChars={}, retrievalMs={}",
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
                retrievalMs);

        return new PreparedChat(chatRequest, bundle);
    }

    private record PreparedChat(ChatRequest chatRequest, ContextBundle bundle) {
    }
}
