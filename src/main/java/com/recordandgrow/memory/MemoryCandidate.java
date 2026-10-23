package com.recordandgrow.memory;

/**
 * 一条候选记忆。由 {@link MemoryExtractor} 产出，落库前统一走
 * {@link MemoryService#addCandidate} 去重与限额。
 *
 * @param proposedType 确认后应落成 FACT 还是 INFERRED
 */
public record MemoryCandidate(
        String content,
        MemoryType proposedType,
        Double confidence,
        String evidence,
        MemorySource source,
        Long conversationId) {

    public MemoryCandidate {
        proposedType = proposedType == null ? MemoryType.INFERRED : proposedType;
        source = source == null ? MemorySource.AI_EXTRACTED : source;
    }
}
