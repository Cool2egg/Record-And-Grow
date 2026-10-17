package com.recordandgrow.ai;

/**
 * 模型停止生成的原因，映射 OpenAI 兼容接口的 {@code finish_reason}。
 */
public enum FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    INSUFFICIENT_RESOURCE,
    ABORTED,
    UNKNOWN;

    public static FinishReason fromWire(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "stop" -> STOP;
            case "length" -> LENGTH;
            case "content_filter" -> CONTENT_FILTER;
            case "tool_calls" -> TOOL_CALLS;
            case "insufficient_system_resource" -> INSUFFICIENT_RESOURCE;
            case "aborted" -> ABORTED;
            default -> UNKNOWN;
        };
    }
}
