package com.recordandgrow.ai;

/**
 * 一次模型调用的 token 用量。
 */
public record ChatUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static ChatUsage empty() {
        return new ChatUsage(0, 0, 0);
    }
}
