package com.recordandgrow.ai;

import java.util.Objects;

/**
 * 一条对话消息。本模块只支持文本消息。
 */
public record ChatMessage(Role role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role 不能为空");
        content = content == null ? "" : content;
    }

    public enum Role {
        SYSTEM, USER, ASSISTANT
    }
}
