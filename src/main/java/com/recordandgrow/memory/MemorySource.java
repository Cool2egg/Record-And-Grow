package com.recordandgrow.memory;

/**
 * 记忆来源，用于区分“用户说的”和“AI 猜的”。
 */
public enum MemorySource {

    /** 用户主动录入。 */
    USER,

    /** AI 从对话里提炼。 */
    AI_EXTRACTED,

    /** AI 从文档里提炼。 */
    AI_DOCUMENT
}
