package com.recordandgrow.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Server 冒烟：真实 Tomcat + stateless transport，确认 initialize 与 tools/list 可用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void initializeAndListTools() throws Exception {
        HttpResponse<String> init = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2024-11-05","capabilities":{},
                  "clientInfo":{"name":"test","version":"1.0"}}}
                """);
        assertThat(init.statusCode()).isBetween(200, 299);
        assertThat(init.body()).contains("recordandgrow");

        HttpResponse<String> tools = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        assertThat(tools.statusCode()).isBetween(200, 299);
        assertThat(tools.body())
                .contains("search_timeline")
                .contains("search_documents")
                .contains("search_memory")
                .contains("search_graph");
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
