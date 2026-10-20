package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务对话配置：时间窗、上下文预算、回答长度。
 *
 * <p>与 {@code recordandgrow.ai.*} 分开：AI 配置描述“怎么连模型”，
 * 这里描述“怎么检索、放多少上下文、回答多长”。
 */
@ConfigurationProperties(prefix = "recordandgrow.chat")
public class ChatProperties {

    /** 计算“今天/上周/10月”等时间表达所用的时区。 */
    private String timezone = "Asia/Shanghai";

    /** 问题里没有时间表达时的默认窗口（天）。 */
    private int defaultWindowDays = 30;

    /** 时间窗上限（天），超出时从起点夹紧。 */
    private int maxWindowDays = 365;

    /** 单次检索最多取回多少条（同时受 TimelineEntryService.MAX_PAGE_SIZE 限制）。 */
    private int maxEntries = 40;

    /** Prompt 中单条记录的字符上限（第一档）。 */
    private int maxEntryChars = 500;

    /** 仍超预算时单条记录的第二档字符上限。 */
    private int minEntryChars = 300;

    /** system + user prompt 的总字符预算。 */
    private int maxContextChars = 8000;

    /** 记录区自己的字符上限，与总预算取较小值。 */
    private int maxRecordsChars = 6000;

    /** 问题最大字符数（运行时校验）。 */
    private int maxQuestionChars = 2000;

    /** 请求未指定 maxTokens 时的默认值。 */
    private int answerMaxTokens = 1024;

    /** 请求可指定的 maxTokens 硬上限。 */
    private int maxAnswerTokens = 4096;

    /** 请求未指定 temperature 时的默认值。 */
    private double temperature = 0.3;

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getDefaultWindowDays() {
        return defaultWindowDays;
    }

    public void setDefaultWindowDays(int defaultWindowDays) {
        this.defaultWindowDays = defaultWindowDays;
    }

    public int getMaxWindowDays() {
        return maxWindowDays;
    }

    public void setMaxWindowDays(int maxWindowDays) {
        this.maxWindowDays = maxWindowDays;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public int getMaxEntryChars() {
        return maxEntryChars;
    }

    public void setMaxEntryChars(int maxEntryChars) {
        this.maxEntryChars = maxEntryChars;
    }

    public int getMinEntryChars() {
        return minEntryChars;
    }

    public void setMinEntryChars(int minEntryChars) {
        this.minEntryChars = minEntryChars;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    public int getMaxRecordsChars() {
        return maxRecordsChars;
    }

    public void setMaxRecordsChars(int maxRecordsChars) {
        this.maxRecordsChars = maxRecordsChars;
    }

    public int getMaxQuestionChars() {
        return maxQuestionChars;
    }

    public void setMaxQuestionChars(int maxQuestionChars) {
        this.maxQuestionChars = maxQuestionChars;
    }

    public int getAnswerMaxTokens() {
        return answerMaxTokens;
    }

    public void setAnswerMaxTokens(int answerMaxTokens) {
        this.answerMaxTokens = answerMaxTokens;
    }

    public int getMaxAnswerTokens() {
        return maxAnswerTokens;
    }

    public void setMaxAnswerTokens(int maxAnswerTokens) {
        this.maxAnswerTokens = maxAnswerTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
