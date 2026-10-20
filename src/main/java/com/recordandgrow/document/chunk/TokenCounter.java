package com.recordandgrow.document.chunk;

/**
 * token 计数器。
 *
 * <p>接口隔离实现：现在用 jtokkit 近似 Qwen 的分词，以后换更精确的实现时，
 * 切块器、验证文档里的口径都不用动。
 */
public interface TokenCounter {

    int count(String text);

    /** 实现标识，写进日志与验证文档，避免以后分不清块是用哪套分词算的。 */
    String name();
}
