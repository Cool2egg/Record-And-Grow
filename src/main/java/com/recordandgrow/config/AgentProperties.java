package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 编排配置（模块十一）。
 *
 * <p>{@code enabled=true} 只表示「允许走 Agent 路径」；真正切换还要看有没有配 key。
 * 没 key 时自动回落到模块四的手写链路，保证无 key 也能启动与演示。
 */
@ConfigurationProperties(prefix = "recordandgrow.agent")
public class AgentProperties {

    /** 是否启用 AgentScope 编排。 */
    private boolean enabled = true;

    /** 检索 Agent 的最大轮数（一次模型调用算一轮）。 */
    private int researchMaxIters = 4;

    /** 检索阶段的整体等待上限。 */
    private Duration researchTimeout = Duration.ofSeconds(45);

    /** 回答阶段的整体等待上限。 */
    private Duration answerTimeout = Duration.ofSeconds(120);

    /** 回答 Prompt 里证据区的字符上限。 */
    private int maxEvidenceChars = 7000;

    /** 单次请求最多收集多少条证据（时间线+文档+记忆+图谱合计）。 */
    private int maxEvidenceItems = 60;

    /** 是否给 Agent 挂记忆工具（search_memory / propose_memory）。 */
    private boolean memoryTools = true;

    /** 是否给 Agent 挂图谱工具（search_graph）。 */
    private boolean graphTools = true;

    /** 是否允许 Agent 提交候选记忆（仍然只写 PENDING）。 */
    private boolean proposeMemory = true;

    /** 检索笔记的最大字符数，只用于日志与回答 Prompt 的提示。 */
    private int maxResearchNotesChars = 600;

    /** 回答的 maxTokens 覆盖；为空时用请求值 / chat 配置。 */
    private Integer answerMaxTokens;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getResearchMaxIters() {
        return researchMaxIters;
    }

    public void setResearchMaxIters(int researchMaxIters) {
        this.researchMaxIters = researchMaxIters;
    }

    public Duration getResearchTimeout() {
        return researchTimeout;
    }

    public void setResearchTimeout(Duration researchTimeout) {
        this.researchTimeout = researchTimeout;
    }

    public Duration getAnswerTimeout() {
        return answerTimeout;
    }

    public void setAnswerTimeout(Duration answerTimeout) {
        this.answerTimeout = answerTimeout;
    }

    public int getMaxEvidenceChars() {
        return maxEvidenceChars;
    }

    public void setMaxEvidenceChars(int maxEvidenceChars) {
        this.maxEvidenceChars = maxEvidenceChars;
    }

    public int getMaxEvidenceItems() {
        return maxEvidenceItems;
    }

    public void setMaxEvidenceItems(int maxEvidenceItems) {
        this.maxEvidenceItems = maxEvidenceItems;
    }

    public boolean isMemoryTools() {
        return memoryTools;
    }

    public void setMemoryTools(boolean memoryTools) {
        this.memoryTools = memoryTools;
    }

    public boolean isGraphTools() {
        return graphTools;
    }

    public void setGraphTools(boolean graphTools) {
        this.graphTools = graphTools;
    }

    public boolean isProposeMemory() {
        return proposeMemory;
    }

    public void setProposeMemory(boolean proposeMemory) {
        this.proposeMemory = proposeMemory;
    }

    public int getMaxResearchNotesChars() {
        return maxResearchNotesChars;
    }

    public void setMaxResearchNotesChars(int maxResearchNotesChars) {
        this.maxResearchNotesChars = maxResearchNotesChars;
    }

    public Integer getAnswerMaxTokens() {
        return answerMaxTokens;
    }

    public void setAnswerMaxTokens(Integer answerMaxTokens) {
        this.answerMaxTokens = answerMaxTokens;
    }
}
