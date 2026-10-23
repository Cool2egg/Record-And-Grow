package com.recordandgrow.memory;

/**
 * 消息角色。只落库用户与助手两种；system prompt 每次现拼，不入库。
 */
public enum MessageRole {
    USER,
    ASSISTANT
}
