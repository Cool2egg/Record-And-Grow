package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档侧融合配置（模块九）。
 *
 * <p>默认关闭：关闭时 {@code DocumentFusionService} 直接转发向量检索的结果，
 * 与模块六行为完全一致。
 */
@ConfigurationProperties(prefix = "recordandgrow.fusion")
public class FusionProperties {

    private boolean enabled = false;

    /**
     * RRF 的 k。经典默认是 60，这里用 20：候选池只有 20–40 条，
     * k=60 会把名次差异压得太平，头部拉不开。
     */
    private int rrfK = 20;

    /** RRF 之后送精排的候选上限。 */
    private int candidateLimit = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }
}
