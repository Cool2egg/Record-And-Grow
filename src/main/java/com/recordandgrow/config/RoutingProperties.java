package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 意图路由配置。默认关闭：关闭时行为与模块六完全一致（两路都查），
 * 打开后每次提问多一次小模型调用。
 */
@ConfigurationProperties(prefix = "recordandgrow.routing")
public class RoutingProperties {

    /** 关闭时不做分类，直接按 BOTH 检索。 */
    private boolean enabled = false;

    /**
     * 分类调用允许的等待上限。超时按兜底处理（查两路），不会拖住主链路。
     *
     * <p>注意：超时只是**放弃等待**，上游 HTTP 请求仍在后台跑完；对分类这种小请求可以接受，
     * 真正要止损需要在客户端层支持取消。
     */
    private Duration timeout = Duration.ofMillis(1500);

    /** 送进分类 Prompt 的问题最大字符数，避免超长问题把分类成本拉高。 */
    private int maxQuestionChars = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxQuestionChars() {
        return maxQuestionChars;
    }

    public void setMaxQuestionChars(int maxQuestionChars) {
        this.maxQuestionChars = maxQuestionChars;
    }
}
