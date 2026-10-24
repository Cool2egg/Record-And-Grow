package com.recordandgrow.controller;

import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.config.AgentProperties;
import com.recordandgrow.config.GraphProperties;
import com.recordandgrow.infra.response.ApiResponse;
import com.recordandgrow.skill.SkillRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 系统状态接口（模块十三 §3.6）。
 *
 * <p>状态页要能回答「它到底跑起来没有」：数据库与 pgvector 看 {@code /api/health}，
 * 这里补上版本、启动时间、模型 key 是否配置、图谱后端与技能数量。
 * 不发起任何真实模型调用，避免打开状态页就产生费用。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ChatClient chatClient;
    private final EmbeddingClient embeddingClient;
    private final AgentProperties agentProperties;
    private final GraphProperties graphProperties;
    private final boolean mcpEnabled;
    private final SkillRegistry skillRegistry;
    private final String version;

    public SystemController(ChatClient chatClient,
                            EmbeddingClient embeddingClient,
                            AgentProperties agentProperties,
                            GraphProperties graphProperties,
                            @Value("${recordandgrow.mcp.enabled:true}") boolean mcpEnabled,
                            SkillRegistry skillRegistry,
                            @Value("${recordandgrow.system.version:0.9.9}") String version) {
        this.chatClient = chatClient;
        this.embeddingClient = embeddingClient;
        this.agentProperties = agentProperties;
        this.graphProperties = graphProperties;
        this.mcpEnabled = mcpEnabled;
        this.skillRegistry = skillRegistry;
        this.version = version;
    }

    @GetMapping
    public ApiResponse<SystemStatus> status() {
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
                ZoneId.systemDefault()).withOffsetSameInstant(ZoneOffset.UTC);
        return ApiResponse.success(new SystemStatus(
                "record-and-grow",
                version,
                startedAt,
                System.getProperty("java.version"),
                chatClient.isConfigured(),
                embeddingClient.isConfigured(),
                agentProperties.isEnabled(),
                graphProperties.isEnabled(),
                graphProperties.getStore(),
                mcpEnabled,
                skillRegistry.all()));
    }

    /**
     * @param aiConfigured       对话模型 key 是否配置（只看空白，不发起请求）
     * @param embeddingConfigured 向量模型 key 是否配置（影响文档检索与入库）
     * @param skills             已加载技能；状态页直接展示，不再单独请求一次
     */
    public record SystemStatus(
            String name,
            String version,
            OffsetDateTime startedAt,
            String javaVersion,
            boolean aiConfigured,
            boolean embeddingConfigured,
            boolean agentEnabled,
            boolean graphEnabled,
            String graphStore,
            boolean mcpEnabled,
            List<com.recordandgrow.skill.Skill> skills) {
    }
}
