package com.recordandgrow.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.recordandgrow.config.MemoryProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.memory.dto.MemoryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 长期记忆业务逻辑。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>AI 只能写 {@link MemoryType#PENDING}，确认/拒绝必须由用户触发；</li>
 *   <li>只有 FACT / INFERRED 能进检索与回答；</li>
 *   <li>同内容只保留一条，先到先得。</li>
 * </ol>
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 单页上限，避免记忆页一次拉全量。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final MemoryMapper mapper;
    private final MemoryProperties properties;

    public MemoryService(MemoryMapper mapper, MemoryProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public MemoryResponse createManual(String content) {
        String trimmed = content.trim();
        String normalized = normalize(trimmed);

        Memory existing = mapper.selectByNormalized(normalized);
        if (existing != null) {
            if (MemoryType.PENDING.name().equals(existing.getType())) {
                // 用户手工录入等价于确认这条候选
                return confirm(existing.getId());
            }
            return MemoryResponse.from(existing);
        }

        Memory memory = new Memory();
        memory.setType(MemoryType.FACT.name());
        memory.setContent(trimmed);
        memory.setNormalized(normalized);
        memory.setSource(MemorySource.USER.name());
        memory.setConfirmedAt(OffsetDateTime.now());
        mapper.insert(memory);
        return MemoryResponse.from(memory);
    }

    public PageResult<MemoryResponse> list(MemoryType type, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<>();
        if (type != null) {
            wrapper.eq(Memory::getType, type.name());
        }
        wrapper.orderByDesc(Memory::getUpdatedAt).orderByDesc(Memory::getId);

        Page<Memory> result = mapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<MemoryResponse> items = result.getRecords().stream()
                .map(MemoryResponse::from)
                .toList();
        return PageResult.of(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public List<MemoryResponse> pending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return mapper.selectPending(safeLimit).stream().map(MemoryResponse::from).toList();
    }

    public long pendingCount() {
        return mapper.countPending();
    }

    /**
     * 确认候选：PENDING → proposedType（缺省 FACT）。已确认的重复调用直接返回，保持幂等。
     */
    public MemoryResponse confirm(Long id) {
        Memory memory = mustExist(id);
        if (!MemoryType.PENDING.name().equals(memory.getType())) {
            return MemoryResponse.from(memory);
        }
        MemoryType target = MemoryType.parse(memory.getProposedType());
        if (target == null || target == MemoryType.PENDING) {
            target = MemoryType.FACT;
        }
        int updated = mapper.confirm(id, target.name());
        if (updated == 0) {
            // 并发下已被另一个请求确认/删除：以库里的最新状态为准
            return MemoryResponse.from(mustExist(id));
        }
        memory.setType(target.name());
        memory.setProposedType(null);
        memory.setConfirmedAt(OffsetDateTime.now());
        memory.setUpdatedAt(OffsetDateTime.now());
        return MemoryResponse.from(memory);
    }

    public void reject(Long id) {
        Memory memory = mustExist(id);
        if (!MemoryType.PENDING.name().equals(memory.getType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有待确认的记忆可以拒绝");
        }
        mapper.deleteById(id);
    }

    public void delete(Long id) {
        mustExist(id);
        mapper.deleteById(id);
    }

    public MemoryResponse get(Long id) {
        return MemoryResponse.from(mustExist(id));
    }

    /** 最近记忆正文，给提炼 Prompt 做去重参考。 */
    public List<String> recentContents(int limit) {
        return mapper.selectRecentContents(Math.max(1, Math.min(limit, MAX_PAGE_SIZE)));
    }

    /**
     * 可信记忆检索。query 为空时返回最近更新的若干条。
     */
    public List<MemoryResponse> search(String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        String normalized = query == null ? "" : normalize(query);
        if (normalized.isEmpty()) {
            LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(Memory::getType, MemoryType.FACT.name(), MemoryType.INFERRED.name())
                    .orderByDesc(Memory::getUpdatedAt)
                    .last("LIMIT " + safeLimit);
            return mapper.selectList(wrapper).stream().map(MemoryResponse::from).toList();
        }
        return mapper.searchTrusted(escapeLike(normalized), safeLimit)
                .stream().map(MemoryResponse::from).toList();
    }

    /**
     * 落一条候选。重复内容、超过待确认上限都返回 {@code null}（安静丢弃，不是错误）。
     */
    public Long addCandidate(MemoryCandidate candidate) {
        if (candidate == null || candidate.content() == null || candidate.content().isBlank()) {
            return null;
        }
        String content = truncate(candidate.content().trim(), 500);
        String normalized = normalize(content);

        if (mapper.countPending() >= properties.getPendingMax()) {
            log.info("memory candidate dropped: pending limit reached");
            return null;
        }
        if (mapper.selectByNormalized(normalized) != null) {
            return null;
        }

        Memory memory = new Memory();
        memory.setType(MemoryType.PENDING.name());
        memory.setProposedType(candidate.proposedType().name());
        memory.setContent(content);
        memory.setNormalized(normalized);
        memory.setEvidence(truncate(candidate.evidence(), 2000));
        memory.setSource(candidate.source().name());
        memory.setConfidence(clamp(candidate.confidence()));
        memory.setConversationId(candidate.conversationId());
        try {
            mapper.insert(memory);
            return memory.getId();
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时提炼出同一句：唯一索引兜底，后到的安静退出
            return null;
        }
    }

    private Memory mustExist(Long id) {
        Memory memory = mapper.selectById(id);
        if (memory == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "记忆不存在");
        }
        return memory;
    }

    /** 小写、压空白、去掉结尾标点；用于去重与检索。 */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[。.！!？?；;，,]+$", "");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static Double clamp(Double confidence) {
        if (confidence == null) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
