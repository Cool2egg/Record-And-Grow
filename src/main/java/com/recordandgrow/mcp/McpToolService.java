package com.recordandgrow.mcp;

import com.recordandgrow.agent.orchestration.AgentToolContext;
import com.recordandgrow.agent.orchestration.ChatAgentTools;
import com.recordandgrow.config.AgentProperties;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.graph.GraphRetriever;
import com.recordandgrow.memory.MemoryService;
import com.recordandgrow.retrieval.TimelineRetriever;
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.retrieval.query.TimeWindowParser;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * MCP 工具的实际执行层。
 *
 * <p>复用 {@link ChatAgentTools}：MCP 与 Agent 走同一套检索与脱敏逻辑，
 * 避免出现「Agent 能查、MCP 查到不一样的东西」。
 *
 * <p>MCP 没有会话概念，因此每次调用新建一个临时上下文，不写消息、不提交记忆。
 */
@Service
public class McpToolService {

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

    public McpToolService(TimelineRetriever timelineRetriever,
                          DocumentFusionService documentFusionService,
                          MemoryService memoryService,
                          GraphRetriever graphRetriever,
                          TimeWindowParser windowParser,
                          KeywordExtractor keywordExtractor,
                          ChatProperties chatProperties,
                          AgentProperties agentProperties,
                          Clock clock,
                          ZoneId zone) {
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

    public String searchTimeline(String query) {
        return tools(query).searchTimeline(query);
    }

    public String searchDocuments(String query) {
        return tools(query).searchDocuments(query);
    }

    public String searchMemory(String query) {
        return tools(query).searchMemory(query);
    }

    public String searchGraph(String query) {
        return tools(query).searchGraph(query);
    }

    private ChatAgentTools tools(String query) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        TimeWindow window = windowParser.parse(query == null ? "" : query);
        AgentToolContext context = new AgentToolContext(null, window,
                keywordExtractor.extract(query), 20, zone);
        return new ChatAgentTools(context, timelineRetriever, documentFusionService,
                memoryService, graphRetriever, windowParser, keywordExtractor,
                chatProperties, agentProperties, clock, zone);
    }
}
