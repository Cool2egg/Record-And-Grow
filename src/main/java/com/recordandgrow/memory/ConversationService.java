package com.recordandgrow.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.config.MemoryProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.memory.dto.ConversationResponse;
import com.recordandgrow.memory.dto.MessageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话与消息的读写。
 *
 * <p>历史加载策略：摘要 + 水位之后的最近 N 条；再按字符预算从新到旧截断。
 * 这样长会话的 Prompt 有上界，且不会把最相关的最近几轮丢掉。
 */
@Service
public class ConversationService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int TITLE_CHARS = 30;
    private static final String DEFAULT_TITLE = "新对话";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MemoryProperties properties;

    public ConversationService(ConversationMapper conversationMapper,
                               MessageMapper messageMapper,
                               MemoryProperties properties) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.properties = properties;
    }

    @Transactional
    public ConversationResponse create(String title) {
        Conversation conversation = new Conversation();
        conversation.setTitle(title == null || title.isBlank() ? DEFAULT_TITLE : title.trim());
        conversation.setSummaryUptoMessageId(0L);
        conversation.setMessageCount(0);
        conversationMapper.insert(conversation);
        return ConversationResponse.from(conversation);
    }

    /**
     * 有 id 就取（不存在报 404），没有就新建。标题取首条问题，便于会话列表辨认。
     */
    @Transactional
    public Conversation getOrCreate(Long conversationId, String firstQuestion) {
        if (conversationId != null) {
            return mustExist(conversationId);
        }
        Conversation conversation = new Conversation();
        conversation.setTitle(titleFrom(firstQuestion));
        conversation.setSummaryUptoMessageId(0L);
        conversation.setMessageCount(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    public ConversationResponse get(Long id) {
        return ConversationResponse.from(mustExist(id));
    }

    public PageResult<ConversationResponse> list(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Conversation::getUpdatedAt).orderByDesc(Conversation::getId);
        Page<Conversation> result = conversationMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(ConversationResponse::from).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public void append(Long conversationId, MessageRole role, String content, String sourcesJson) {
        mustExist(conversationId);
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role.name());
        message.setContent(content);
        message.setSources(sourcesJson);
        message.setTokenCount(0);
        messageMapper.insert(message);
        conversationMapper.bumpMessageCount(conversationId);
    }

    /** 会话的标题仍是默认值时，用第一条用户问题补上。 */
    @Transactional
    public void ensureTitle(Long conversationId, String question) {
        Conversation conversation = mustExist(conversationId);
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversationMapper.updateTitle(conversationId, titleFrom(question));
        }
    }

    public ConversationHistory history(Long conversationId) {
        Conversation conversation = mustExist(conversationId);
        long upto = conversation.getSummaryUptoMessageId() == null
                ? 0 : conversation.getSummaryUptoMessageId();

        int max = Math.max(2, properties.getHistoryMaxMessages());
        List<Message> recent = new ArrayList<>(messageMapper.selectRecent(conversationId, max));
        Collections.reverse(recent);
        recent.removeIf(message -> message.getId() <= upto);

        int budget = Math.max(200, properties.getHistoryMaxChars());
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            int length = message.getContent() == null ? 0 : message.getContent().length();
            if (!kept.isEmpty() && budget - length < 0) {
                break;
            }
            budget -= length;
            ChatMessage.Role role = MessageRole.USER.name().equals(message.getRole())
                    ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT;
            kept.add(0, new ChatMessage(role, message.getContent()));
        }
        return new ConversationHistory(conversation.getSummary(), kept);
    }

    public PageResult<MessageResponse> messages(Long conversationId, int page, int size) {
        mustExist(conversationId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId);
        Page<Message> result = messageMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(MessageResponse::from).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public void delete(Long id) {
        mustExist(id);
        conversationMapper.deleteById(id);
    }

    public Conversation mustExist(Long id) {
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    private static String titleFrom(String question) {
        if (question == null || question.isBlank()) {
            return DEFAULT_TITLE;
        }
        String trimmed = question.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= TITLE_CHARS ? trimmed : trimmed.substring(0, TITLE_CHARS) + "…";
    }
}
