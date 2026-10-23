package com.recordandgrow.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.config.GraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体关系抽取。一次 LLM 调用把一篇文档压成若干三元组。
 *
 * <p>失败/输出不合法一律返回空列表：图是补充证据，不能因为抽取失败让文档入库失败。
 */
@Component
public class GraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractor.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT_TOKENS = 1200;
    private static final double TEMPERATURE = 0.0;
    private static final int MAX_TRIPLES_HARD = 60;

    static final String SYSTEM_PROMPT = """
            你是知识图谱抽取器。从给定文档中抽取「实体 - 关系 - 实体」三元组。

            要求：
            1. 只抽取文档明确支持的关系，不要用常识补全；
            2. 实体用文档里的原词，2–20 字，不要代词；
            3. 关系用简短动词短语，2–10 字，例如「属于」「需要」「推荐」「包含」；
            4. 忽略目录、页眉页脚、免责声明；
            5. 最多输出 15 条，宁缺毋滥。

            只输出 JSON 数组，不要解释、不要代码块：
            [{"source":"实体A","relation":"关系","target":"实体B"}]
            """;

    private final ChatClient chatClient;
    private final GraphProperties properties;

    public GraphExtractor(ChatClient chatClient, GraphProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    public List<GraphTriple> extract(String title, String text) {
        if (!properties.isEnabled() || !chatClient.isConfigured()
                || text == null || text.isBlank()) {
            return List.of();
        }
        String content = truncate(text, Math.max(500, properties.getExtractionMaxChars()));
        String userPrompt = "【文档标题】" + (title == null ? "" : title) + "\n\n【正文】\n" + content;
        try {
            ChatResponse response = chatClient.complete(new ChatRequest(
                    List.of(new ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM_PROMPT),
                            new ChatMessage(ChatMessage.Role.USER, userPrompt)),
                    MAX_OUTPUT_TOKENS,
                    TEMPERATURE));
            return parse(response == null ? null : response.content(),
                    properties.getMaxTriplesPerDocument());
        } catch (RuntimeException e) {
            log.warn("graph extraction failed: title={}, type={}",
                    title, e.getClass().getSimpleName());
            return List.of();
        }
    }

    static List<GraphTriple> parse(String content, int max) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = content.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(max, MAX_TRIPLES_HARD));
        try {
            JsonNode array = JSON.readTree(text.substring(start, end + 1));
            if (!array.isArray()) {
                return List.of();
            }
            List<GraphTriple> triples = new ArrayList<>();
            for (JsonNode node : array) {
                GraphTriple triple = new GraphTriple(
                        node.path("source").asText(""),
                        node.path("relation").asText(""),
                        node.path("target").asText(""));
                if (triple.isValid()) {
                    triples.add(triple);
                }
                if (triples.size() >= limit) {
                    break;
                }
            }
            return triples;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
