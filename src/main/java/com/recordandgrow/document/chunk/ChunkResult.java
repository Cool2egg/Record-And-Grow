package com.recordandgrow.document.chunk;

import java.util.List;

/**
 * 切块结果。
 *
 * @param chunks    按 charStart 递增排列的分块
 * @param truncated 是否因为超过 maxChunks 被截断
 */
public record ChunkResult(List<Chunk> chunks, boolean truncated) {
}
