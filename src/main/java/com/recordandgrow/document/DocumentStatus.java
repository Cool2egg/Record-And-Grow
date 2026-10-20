package com.recordandgrow.document;

/**
 * 文档处理状态。
 *
 * <p>数据库列是 VARCHAR，实体里存字符串而不用 {@code @EnumValue}：
 * 少一处 MyBatis 枚举处理配置，排查时 psql 里看到的也是可读值。
 */
public enum DocumentStatus {

    /** 已落库，等待 parse 消息被消费。 */
    PENDING,
    /** 正在解析与切块。 */
    PARSING,
    /** 分块已落库，等待按批回填向量。 */
    EMBEDDING,
    /** 全部块已回填向量。 */
    READY,
    /** 重试耗尽或解析失败，等待 reindex 或人工处理。 */
    FAILED
}
