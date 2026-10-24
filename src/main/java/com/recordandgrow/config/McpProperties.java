package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP Server 配置（模块十二）。把个人上下文能力暴露给外部 MCP 客户端。
 */
@ConfigurationProperties(prefix = "recordandgrow.mcp")
public class McpProperties {

    private boolean enabled = true;

    /** 对外挂载路径；客户端填 {@code http://host:8080/mcp}。 */
    private String endpoint = "/mcp";

    private String serverName = "recordandgrow";

    private String serverVersion = "0.9.9";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }
}
