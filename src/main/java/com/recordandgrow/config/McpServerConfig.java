package com.recordandgrow.config;

import com.recordandgrow.mcp.McpToolService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 装配（模块十二）。
 *
 * <p>用官方 MCP Java SDK 的 stateless HTTP transport，挂载 {@code /mcp}。
 * 外部客户端（Claude Desktop、AgentScope 的 MCP 客户端等）可通过它复用
 * 时间线 / 文档 / 记忆 / 图谱四类只读能力；不暴露写接口。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "recordandgrow.mcp", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    private static final String QUERY_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string","description":"自然语言问题或关键词"}},"required":["query"]}
            """;

    @Bean
    public McpJsonMapper mcpJsonMapper() {
        // createDefault() 会带上 MCP 协议需要的 Jackson 模块；直接 new ObjectMapper 会缺 mixin，
        // 表现为 initialize 请求被判定为 Invalid message format。
        return McpJsonMapper.createDefault();
    }

    @Bean
    public HttpServletStatelessServerTransport mcpTransport(McpJsonMapper mcpJsonMapper,
                                                            McpProperties properties) {
        return HttpServletStatelessServerTransport.builder()
                .messageEndpoint(properties.getEndpoint())
                .jsonMapper(mcpJsonMapper)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
            HttpServletStatelessServerTransport transport, McpProperties properties) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, properties.getEndpoint());
        registration.setName("recordandgrow-mcp");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport,
                                            McpJsonMapper mcpJsonMapper,
                                            McpToolService tools,
                                            McpProperties properties) {
        return McpServer.sync(transport)
                .serverInfo(properties.getServerName(), properties.getServerVersion())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .jsonMapper(mcpJsonMapper)
                .tools(toolSpecifications(mcpJsonMapper, tools))
                .build();
    }

    private static List<McpStatelessServerFeatures.SyncToolSpecification> toolSpecifications(
            McpJsonMapper mapper, McpToolService tools) {
        return List.of(
                spec(mapper, "search_timeline",
                        "查用户自己的时间线记录（做过什么、什么时候做的）",
                        (arguments) -> tools.searchTimeline(query(arguments))),
                spec(mapper, "search_documents",
                        "查用户上传的资料、笔记、条款、方法",
                        (arguments) -> tools.searchDocuments(query(arguments))),
                spec(mapper, "search_memory",
                        "查用户已确认的长期偏好、目标与事实",
                        (arguments) -> tools.searchMemory(query(arguments))),
                spec(mapper, "search_graph",
                        "查文档知识图谱里的实体关系",
                        (arguments) -> tools.searchGraph(query(arguments))));
    }

    private static McpStatelessServerFeatures.SyncToolSpecification spec(
            McpJsonMapper mapper, String name, String description,
            java.util.function.Function<Map<String, Object>, String> handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(mapper, QUERY_SCHEMA)
                .build();
        return new McpStatelessServerFeatures.SyncToolSpecification(tool,
                (context, request) -> {
                    try {
                        return new McpSchema.CallToolResult(handler.apply(request.arguments()), false);
                    } catch (RuntimeException e) {
                        return new McpSchema.CallToolResult(
                                "{\"error\":\"工具执行失败\"}", true);
                    }
                });
    }

    private static String query(Map<String, Object> arguments) {
        if (arguments == null) {
            return "";
        }
        Object value = arguments.get("query");
        return value == null ? "" : String.valueOf(value);
    }
}
