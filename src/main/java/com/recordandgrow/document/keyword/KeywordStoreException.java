package com.recordandgrow.document.keyword;

/**
 * ES 调用失败的统一包装。
 *
 * <p>存在的意义是让调用方能**精确识别**"关键词通道挂了"，而不是捕到一个裸
 * {@code RuntimeException} 顺手吞掉——后者会把程序 bug 也当成降级。
 */
public class KeywordStoreException extends RuntimeException {

    private final String operation;

    public KeywordStoreException(String operation, Throwable cause) {
        super("关键词通道 " + operation + " 失败: " + cause.getMessage(), cause);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }
}
