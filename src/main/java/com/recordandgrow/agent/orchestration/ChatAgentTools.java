package com.recordandgrow.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordandgrow.config.AgentProperties;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.graph.GraphRetrievalResult;
import com.recordandgrow.graph.GraphRetriever;
import com.recordandgrow.memory.MemoryCandidate;
import com.recordandgrow.memory.MemoryService;
import com.recordandgrow.memory.MemoryType;
import com.recordandgrow.memory.MemorySource;
import com.recordandgrow.memory.dto.MemoryResponse;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.retrieval.TimelineRetriever;
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import com.recordandgrow.retrieval.query.TimeBasis;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.retrieval.query.TimeWindowParser;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给检索 Agent 用的工具集。每个请求 new 一个实例，内部绑定该请求的
 * {@link AgentToolContext}，因此不需要 ThreadLocal，也不存在跨请求串数据。
 *
 * <p>所有工具都返回 JSON 字符串；失败返回 {@code {"error":"..."}} 而不是抛异常，
 * 让 Agent 能换关键词重试，而不是整轮崩掉。
 */
public class ChatAgentTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ITEMS = 8;
    private static final int ITEM_CHARS = 220;

    private final AgentToolContext context;
    private final TimelineRetriever timelineRetriever;
    private final DocumentFusionService documentFusionService;
    private final MemoryService memoryService;
    private final GraphRetriever graphRetriever;
    private final TimeWindowParser windowParser;
    private final KeywordExtractor keywordExtractor;
    private final ChatProperties chatProperties;
    private final AgentProperties agentProperties;
    private final Clock clock;
    private final ZoneId zone;

    public ChatAgentTools(AgentToolContext context,
                          TimelineRetriever timelineRetriever,
                          DocumentFusionService documentFusionService,
                          MemoryService memoryService,
                          GraphRetriever graphRetriever,
                          TimeWindowParser windowParser,
                          KeywordExtractor keywordExtractor,
                          ChatProperties chatProperties,
                          AgentProperties agentProperties,
                          Clock clock,
                          ZoneId zone) {
        this.context = context;
        this.timelineRetriever = timelineRetriever;
        this.documentFusionService = documentFusionService;
        this.memoryService = memoryService;
        this.graphRetriever = graphRetriever;
        this.windowParser = windowParser;
        this.keywordExtractor = keywordExtractor;
        this.chatProperties = chatProperties;
        this.agentProperties = agentProperties;
        this.clock = clock;
        this.zone = zone;
    }

    @Tool(name = "search_timeline",
            description = "查用户自己的时间线记录（做过什么、什么时候做的）。query 用自然语言，可带时间表达，例如「上个月跑步」「最近 7 天」。")
    public String searchTimeline(
            @ToolParam(name = "query", description = "检索问题或关键词，例如「上个月跑步」") String query) {
        context.recordToolCall("search_timeline");
        long started = System.nanoTime();
        try {
            TimeWindow window = windowParser.parse(query);
            List<String> keywords = keywordExtractor.extract(query);
            RetrievalResult result = timelineRetriever.retrieve(window, keywords);
            List<EvidenceItem> added = context.addTimeline(result);
            return stepOk("search_timeline", query, started, result.retrieved(), json(Map.of(
                    "count", result.retrieved(),
                    "matched", result.matched(),
                    "items", itemList(added))));
        } catch (RuntimeException e) {
            return stepFailed("search_timeline", query, started, "时间线检索失败");
        }
    }

    @Tool(name = "get_timeline_by_date",
            description = "查某一天的全部时间线记录。date 格式 yyyy-MM-dd。")
    public String getTimelineByDate(
            @ToolParam(name = "date", description = "日期，格式 yyyy-MM-dd，例如 2026-10-01") String date) {
        context.recordToolCall("get_timeline_by_date");
        long started = System.nanoTime();
        try {
            LocalDate localDate = LocalDate.parse(date == null ? "" : date.trim());
            OffsetDateTime start = localDate.atStartOfDay(zone).toOffsetDateTime();
            TimeWindow window = new TimeWindow(start, start.plusDays(1), TimeBasis.ABSOLUTE_DAY, false);
            RetrievalResult result = timelineRetriever.retrieve(window, List.of());
            List<EvidenceItem> added = context.addTimeline(result);
            return stepOk("get_timeline_by_date", date, started, result.retrieved(), json(Map.of(
                    "date", localDate.toString(),
                    "count", result.retrieved(),
                    "items", itemList(added))));
        } catch (DateTimeParseException e) {
            return stepFailed("get_timeline_by_date", date, started, "日期格式应为 yyyy-MM-dd");
        } catch (RuntimeException e) {
            return stepFailed("get_timeline_by_date", date, started, "时间线检索失败");
        }
    }

    @Tool(name = "search_documents",
            description = "查用户上传的资料、笔记、条款、方法。返回文档块与出处，用于知识类问题。")
    public String searchDocuments(
            @ToolParam(name = "query", description = "检索问题或关键词") String query) {
        context.recordToolCall("search_documents");
        long started = System.nanoTime();
        try {
            DocumentRetrievalResult result = documentFusionService.retrieve(query);
            List<EvidenceItem> added = context.addDocuments(result);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("searched", result.searched());
            payload.put("degraded", result.degraded());
            payload.put("degradeReason", result.degradeReason() == null
                    ? null : result.degradeReason().name());
            payload.put("count", result.hits().size());
            payload.put("items", itemList(added));
            return stepOk("search_documents", query, started, result.hits().size(), json(payload));
        } catch (RuntimeException e) {
            return stepFailed("search_documents", query, started, "文档检索失败");
        }
    }

    @Tool(name = "search_memory",
            description = "查用户已确认的长期偏好、目标、身份与习惯。只返回可信记忆，不返回待确认候选。")
    public String searchMemory(
            @ToolParam(name = "query", description = "要查的偏好或事实关键词") String query) {
        context.recordToolCall("search_memory");
        long started = System.nanoTime();
        if (!agentProperties.isMemoryTools()) {
            return stepOk("search_memory", query, started, 0,
                    json(Map.of("count", 0, "items", List.of())));
        }
        try {
            List<MemoryResponse> memories = memoryService.search(query, 5);
            List<EvidenceItem> added = context.addMemories(memories);
            return stepOk("search_memory", query, started, memories.size(),
                    json(Map.of("count", memories.size(), "items", itemList(added))));
        } catch (RuntimeException e) {
            return stepFailed("search_memory", query, started, "记忆检索失败");
        }
    }

    @Tool(name = "search_graph",
            description = "查文档知识图谱里的实体关系，适合「A 和 B 有什么关系」「某个概念属于什么」这类问题。")
    public String searchGraph(
            @ToolParam(name = "query", description = "实体名或关系问题") String query) {
        context.recordToolCall("search_graph");
        long started = System.nanoTime();
        if (!agentProperties.isGraphTools()) {
            return stepOk("search_graph", query, started, 0,
                    json(Map.of("count", 0, "items", List.of())));
        }
        try {
            GraphRetrievalResult result = graphRetriever.retrieve(query);
            List<EvidenceItem> added = context.addGraph(result);
            return stepOk("search_graph", query, started, result.hits().size(), json(Map.of(
                    "searched", result.searched(),
                    "degraded", result.degraded(),
                    "count", result.hits().size(),
                    "items", itemList(added))));
        } catch (RuntimeException e) {
            return stepFailed("search_graph", query, started, "图谱检索失败");
        }
    }

    @Tool(name = "propose_memory",
            description = "把稳定的长期事实/偏好提交为待确认记忆。只提交长期有效的信息，不要提交一次性事件；用户确认后才会生效。")
    public String proposeMemory(
            @ToolParam(name = "content", description = "一条记忆，60 字以内，例如「用户每天早起跑步」") String content,
            @ToolParam(name = "type", description = "FACT（用户明确陈述）或 INFERRED（你的归纳）") String type) {
        context.recordToolCall("propose_memory");
        long started = System.nanoTime();
        if (!agentProperties.isProposeMemory() || content == null || content.isBlank()) {
            return stepOk("propose_memory", content, started, 0, json(Map.of("saved", false)));
        }
        MemoryType proposed = MemoryType.parse(type);
        if (proposed == null || proposed == MemoryType.PENDING) {
            proposed = MemoryType.INFERRED;
        }
        try {
            Long id = memoryService.addCandidate(new MemoryCandidate(
                    content,
                    proposed,
                    0.7,
                    "[{\"type\":\"conversation\",\"id\":" + context.conversationId()
                            + ",\"excerpt\":\"" + escape(truncate(content, 80)) + "\"}]",
                    MemorySource.AI_EXTRACTED,
                    context.conversationId()));
            return stepOk("propose_memory", content, started, id == null ? 0 : 1,
                    json(Map.of("saved", id != null, "pendingId", id == null ? 0L : id)));
        } catch (RuntimeException e) {
            return stepFailed("propose_memory", content, started, "记忆提交失败");
        }
    }

    /** 记录一条成功 step，并把工具原始结果原样返回给模型。 */
    private String stepOk(String tool, String query, long startedNanos, int hits, String payload) {
        context.recordStep(tool, query, hits, true, elapsedMs(startedNanos), null);
        return payload;
    }

    /** 记录一条失败 step；返回值仍是给模型看的 {@code {"error": ...}}。 */
    private String stepFailed(String tool, String query, long startedNanos, String message) {
        context.recordStep(tool, query, 0, false, elapsedMs(startedNanos), message);
        return error(message);
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static List<Map<String, Object>> itemList(List<EvidenceItem> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < items.size() && i < MAX_ITEMS; i++) {
            EvidenceItem item = items.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ref", item.ref());
            map.put("kind", item.kind());
            if (item.kind().equals("timeline") && item.startTime() != null) {
                map.put("time", item.startTime().toString());
            }
            if (item.title() != null) {
                map.put("title", item.title());
            }
            map.put("text", truncate(item.text(), ITEM_CHARS));
            list.add(map);
        }
        return list;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    private static String error(String message) {
        return json(Map.of("error", message));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 供单测断言：本请求实际绑定到的上下文。 */
    AgentToolContext context() {
        return context;
    }
}
