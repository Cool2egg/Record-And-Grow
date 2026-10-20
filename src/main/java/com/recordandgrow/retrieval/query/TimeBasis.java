package com.recordandgrow.retrieval.query;

/**
 * 时间窗的来源类型，只用于日志、sources 元信息和测试断言。
 *
 * <p>Prompt 里不出现枚举名，使用 {@link #specified()} 生成中文说明。
 */
public enum TimeBasis {

    RELATIVE_DAY,
    RELATIVE_WEEK,
    RELATIVE_MONTH,
    RELATIVE_RECENT,
    ABSOLUTE_DAY,
    ABSOLUTE_MONTH,
    ABSOLUTE_YEAR,
    DEFAULT;

    public boolean specified() {
        return this != DEFAULT;
    }
}
