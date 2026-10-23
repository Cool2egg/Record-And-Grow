package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 记忆系统配置（模块十）。
 *
 * <p>默认开启：没配 key 时提炼与摘要会被静默跳过，不影响对话主链路。
 */
@ConfigurationProperties(prefix = "recordandgrow.memory")
public class MemoryProperties {

    /** 记忆系统总开关；关闭后不加载历史、不提炼、不提供工具。 */
    private boolean enabled = true;

    /** 每轮带进 Prompt 的最近消息条数（user + assistant 合计）。 */
    private int historyMaxMessages = 12;

    /** 历史消息的字符总预算，超出从最旧丢起。 */
    private int historyMaxChars = 4000;

    /** 消息数超过它时触发一次滚动摘要。 */
    private int summaryThreshold = 20;

    /** 摘要后保留的最近原文条数，不进入摘要。 */
    private int summaryKeep = 6;

    /** 摘要本身的最大字符数。 */
    private int summaryMaxChars = 800;

    /** 是否在回答后异步提炼候选记忆。 */
    private boolean extractionEnabled = true;

    /** 单轮最多产生几条候选，防止刷屏。 */
    private int extractionMaxCandidates = 2;

    /** 待确认候选上限，超过后拒绝新候选（保留最早的）。 */
    private int pendingMax = 50;

    /** 记忆检索工具默认返回条数。 */
    private int searchLimit = 5;

    /** 异步任务（摘要 / 提炼）的单次等待上限。 */
    private Duration asyncTimeout = Duration.ofSeconds(20);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getHistoryMaxMessages() {
        return historyMaxMessages;
    }

    public void setHistoryMaxMessages(int historyMaxMessages) {
        this.historyMaxMessages = historyMaxMessages;
    }

    public int getHistoryMaxChars() {
        return historyMaxChars;
    }

    public void setHistoryMaxChars(int historyMaxChars) {
        this.historyMaxChars = historyMaxChars;
    }

    public int getSummaryThreshold() {
        return summaryThreshold;
    }

    public void setSummaryThreshold(int summaryThreshold) {
        this.summaryThreshold = summaryThreshold;
    }

    public int getSummaryKeep() {
        return summaryKeep;
    }

    public void setSummaryKeep(int summaryKeep) {
        this.summaryKeep = summaryKeep;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }

    public boolean isExtractionEnabled() {
        return extractionEnabled;
    }

    public void setExtractionEnabled(boolean extractionEnabled) {
        this.extractionEnabled = extractionEnabled;
    }

    public int getExtractionMaxCandidates() {
        return extractionMaxCandidates;
    }

    public void setExtractionMaxCandidates(int extractionMaxCandidates) {
        this.extractionMaxCandidates = extractionMaxCandidates;
    }

    public int getPendingMax() {
        return pendingMax;
    }

    public void setPendingMax(int pendingMax) {
        this.pendingMax = pendingMax;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public Duration getAsyncTimeout() {
        return asyncTimeout;
    }

    public void setAsyncTimeout(Duration asyncTimeout) {
        this.asyncTimeout = asyncTimeout;
    }
}
