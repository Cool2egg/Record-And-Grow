package com.recordandgrow.ai;

/**
 * 非流式对话结果。
 */
public record ChatResponse(String content, FinishReason finishReason, ChatUsage usage) {
}
