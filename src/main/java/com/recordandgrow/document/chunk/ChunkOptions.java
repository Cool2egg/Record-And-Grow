package com.recordandgrow.document.chunk;

/**
 * 切块参数。
 *
 * <p>单独抽成 record 而不是直接依赖 {@code DocumentProperties}：
 * 切块器是纯函数，不应该认识 Spring 配置类，测试里也能随手构造。
 */
public record ChunkOptions(int maxTokens, int overlapTokens, int minTokens, int maxChunks) {

    public ChunkOptions {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens 必须在 [0, maxTokens) 内");
        }
        if (minTokens < 0) {
            throw new IllegalArgumentException("minTokens 不能为负");
        }
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("maxChunks 必须大于 0");
        }
    }
}
