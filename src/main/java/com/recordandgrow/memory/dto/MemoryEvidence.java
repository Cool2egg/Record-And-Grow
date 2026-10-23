package com.recordandgrow.memory.dto;

/**
 * 记忆证据：这条记忆是从哪条时间线记录 / 文档 / 会话推出来的。
 *
 * @param type timeline / document / conversation
 * @param id   对应实体 id，可能为空
 * @param excerpt 证据摘要
 */
public record MemoryEvidence(String type, Long id, String excerpt) {
}
