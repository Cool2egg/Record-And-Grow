package com.recordandgrow.memory;

import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 会话滚动摘要。
 *
 * <p>触发条件：水位之后的消息数超过 {@code summary-threshold}；每次只把"最旧的一批"
 * 压进摘要，保留最近 {@code summary-keep} 条原文。摘要失败不影响对话，下一轮再试。
 *
 * <p>用「带水位条件」的 UPDATE 落库：两个后台任务并发时，后完成的旧摘要不会覆盖新摘要。
 */
@Service
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);

    private static final int MAX_SUMMARY_OUTPUT_TOKENS = 512;
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_BATCH = 30;

    static final String SYSTEM_PROMPT = """
            你是会话摘要器。把「已有摘要」和「新增对话」合并成一段新的中文摘要。

            要求：
            1. 只保留对后续对话有用的事实：用户的经历、偏好、目标、正在处理的事、已确认的结论。
            2. 删掉寒暄、重复表述和一次性细节。
            3. 不添加对话里没有的信息，不做推断。
            4. 用第三人称陈述，200 字以内，只输出摘要正文。
            """;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChatClient chatClient;
    private final MemoryProperties properties;
    private final ExecutorService executor;

    public ConversationSummarizer(ConversationMapper conversationMapper,
                                  MessageMapper messageMapper,
                                  ChatClient chatClient,
                                  MemoryProperties properties,
                                  @Qualifier("memoryExecutor") ExecutorService executor) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.chatClient = chatClient;
        this.properties = properties;
        this.executor = executor;
    }

    public void summarizeAsync(Long conversationId) {
        if (!properties.isEnabled() || !chatClient.isConfigured()) {
            return;
        }
        executor.execute(() -> summarize(conversationId));
    }

    /**
     * 同步摘要，供后台任务与单测调用。返回是否真的写了摘要。
     */
    public boolean summarize(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return false;
        }
        long upto = conversation.getSummaryUptoMessageId() == null
                ? 0 : conversation.getSummaryUptoMessageId();

        int threshold = Math.max(4, properties.getSummaryThreshold());
        List<Message> after = messageMapper.selectAfter(conversationId, upto, threshold + properties.getSummaryKeep() + 1);
        if (after.size() <= properties.getSummaryKeep()) {
            return false;
        }

        int count = Math.min(after.size() - properties.getSummaryKeep(), MAX_BATCH);
        List<Message> batch = after.subList(0, count);
        String transcript = transcript(batch);
        long newUpto = batch.get(batch.size() - 1).getId();

        String userPrompt = "【已有摘要】\n"
                + (conversation.getSummary() == null || conversation.getSummary().isBlank()
                ? "（无）" : conversation.getSummary())
                + "\n\n【新增对话】\n" + transcript
                + "\n\n请输出合并后的摘要。";

        try {
            ChatResponse response = chatClient.complete(new ChatRequest(
                    List.of(new ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM_PROMPT),
                            new ChatMessage(ChatMessage.Role.USER, userPrompt)),
                    MAX_SUMMARY_OUTPUT_TOKENS,
                    TEMPERATURE));
            String summary = response == null || response.content() == null
                    ? "" : response.content().trim();
            if (summary.isEmpty()) {
                return false;
            }
            conversationMapper.updateSummary(conversationId,
                    truncate(summary, properties.getSummaryMaxChars()), newUpto);
            log.info("conversation summarized: conversationId={}, messages={}, uptoId={}",
                    conversationId, count, newUpto);
            return true;
        } catch (RuntimeException e) {
            // 摘要失败只影响下一轮的上下文长度，不影响对话本身
            log.warn("conversation summary failed: conversationId={}, type={}",
                    conversationId, e.getClass().getSimpleName());
            return false;
        }
    }

    private static String transcript(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            String role = MessageRole.USER.name().equals(message.getRole()) ? "用户" : "助手";
            builder.append(role).append("：")
                    .append(truncate(message.getContent(), 500))
                    .append('\n');
        }
        return builder.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
