package com.recordandgrow.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 回答后的记忆提炼。
 *
 * <p>刻意做成**异步、可失败、可丢弃**：提炼是锦上添花，不能拖慢或拖垮对话。
 * 产出一律是 PENDING，用户确认后才参与回答。
 */
@Service
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT_TOKENS = 512;
    private static final double TEMPERATURE = 0.2;

    static final String SYSTEM_PROMPT = """
            你是个人记忆提炼器。从这一轮对话里找出「以后还会用到」的长期信息：
            稳定偏好、长期目标、身份/角色、长期习惯、重要人际关系、明确的原则。

            不要提取：
            - 一次性事件（今天做了什么、这次问了什么）；
            - 临时情绪和客套话；
            - 助手自己的推测（除非用户明确认可）；
            - 对话里没有的信息。

            每条不超过 60 字。没有可提取的就输出 []。
            只输出 JSON 数组，不要解释、不要代码块：
            [{"content":"...","type":"FACT|INFERRED","confidence":0.0}]

            type 判定：用户明确陈述自己的事实用 FACT；你从对话归纳出的判断用 INFERRED。
            最多输出 2 条。
            """;

    private final ChatClient chatClient;
    private final MemoryService memoryService;
    private final MemoryProperties properties;
    private final ExecutorService executor;

    public MemoryExtractor(ChatClient chatClient,
                           MemoryService memoryService,
                           MemoryProperties properties,
                           @Qualifier("memoryExecutor") ExecutorService executor) {
        this.chatClient = chatClient;
        this.memoryService = memoryService;
        this.properties = properties;
        this.executor = executor;
    }

    public void extractAsync(Long conversationId, String question, String answer) {
        if (!properties.isEnabled() || !properties.isExtractionEnabled()
                || conversationId == null || !chatClient.isConfigured()) {
            return;
        }
        executor.execute(() -> extract(conversationId, question, answer));
    }

    /** 同步提炼，供后台任务与单测调用。返回写入的候选数。 */
    public int extract(Long conversationId, String question, String answer) {
        try {
            StringBuilder userPrompt = new StringBuilder();
            List<String> existing = memoryService.recentContents(20);
            if (!existing.isEmpty()) {
                userPrompt.append("【已有记忆】（不要重复或换说法）\n");
                for (String content : existing) {
                    userPrompt.append("- ").append(truncate(content, 120)).append('\n');
                }
                userPrompt.append('\n');
            }
            userPrompt.append("【用户】\n").append(truncate(question, 1000))
                    .append("\n\n【助手】\n").append(truncate(answer, 2000));
            ChatResponse response = chatClient.complete(new ChatRequest(
                    List.of(new ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM_PROMPT),
                            new ChatMessage(ChatMessage.Role.USER, userPrompt.toString())),
                    MAX_OUTPUT_TOKENS,
                    TEMPERATURE));
            List<MemoryCandidate> candidates = parse(response == null ? null : response.content(),
                    properties.getExtractionMaxCandidates());
            int written = 0;
            for (MemoryCandidate candidate : candidates) {
                Long id = memoryService.addCandidate(new MemoryCandidate(
                        candidate.content(),
                        candidate.proposedType(),
                        candidate.confidence(),
                        evidence(conversationId, question),
                        MemorySource.AI_EXTRACTED,
                        conversationId));
                if (id != null) {
                    written++;
                }
            }
            if (written > 0) {
                log.info("memory candidates extracted: conversationId={}, count={}", conversationId, written);
            }
            return written;
        } catch (RuntimeException e) {
            log.warn("memory extraction failed: conversationId={}, type={}",
                    conversationId, e.getClass().getSimpleName());
            return 0;
        }
    }

    static List<MemoryCandidate> parse(String content) {
        return parse(content, 2);
    }

    /**
     * 解析模型输出。容忍 ```json 围栏与前后废话；解析失败返回空列表，不抛异常。
     */
    static List<MemoryCandidate> parse(String content, int maxCandidates) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, maxCandidates);
        String text = content.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode array = JSON.readTree(text.substring(start, end + 1));
            if (!array.isArray()) {
                return List.of();
            }
            List<MemoryCandidate> candidates = new ArrayList<>();
            for (JsonNode node : array) {
                String value = node.path("content").asText("").trim();
                if (value.isEmpty() || value.length() > 500) {
                    continue;
                }
                MemoryType type = MemoryType.parse(node.path("type").asText("INFERRED"));
                if (type == null || type == MemoryType.PENDING) {
                    type = MemoryType.INFERRED;
                }
                double confidence = node.path("confidence").asDouble(0.6);
                candidates.add(new MemoryCandidate(value, type,
                        Math.max(0.0, Math.min(1.0, confidence)), null, MemorySource.AI_EXTRACTED, null));
                if (candidates.size() >= limit) {
                    break;
                }
            }
            return candidates;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String evidence(Long conversationId, String question) {
        return "[{\"type\":\"conversation\",\"id\":" + conversationId
                + ",\"excerpt\":\"" + escape(truncate(question, 100)) + "\"}]";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
