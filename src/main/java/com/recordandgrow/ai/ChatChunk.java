package com.recordandgrow.ai;

/**
 * 流式对话的一个增量。
 *
 * <p>{@code contentDelta} 可能为空串（例如只带 role 或只带 finish_reason 的块），
 * 调用方应通过 {@link #hasContent()} 判断是否需要转发。
 */
public record ChatChunk(String contentDelta, FinishReason finishReason, ChatUsage usage) {

    public boolean hasContent() {
        return contentDelta != null && !contentDelta.isEmpty();
    }
}
