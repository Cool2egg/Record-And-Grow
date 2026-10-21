package com.recordandgrow.document;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.recordandgrow.document.dto.DocumentRegisterResult;
import com.recordandgrow.document.dto.DocumentResponse;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 文档入库全链路：真实 pgvector + 真实解析/切块 + WireMock 假 embedding。
 *
 * <p>刻意不加 {@code @Transactional}：入库是分批提交的，测试事务会把中间状态一起回滚，
 * 就测不到「第 2 批失败时第 1 批已落库」。每例前后手工清表、清存储目录。
 *
 * <p>embedding 的批大小设成 1：WireMock 不需要按请求条数动态拼响应，集成测试里
 * 只验「全部块都被回填」，10/10/5 的批切分由 {@code DocumentEmbeddingWireMockTest} 覆盖。
 *
 * <p>依赖本机 Docker，沙箱跑不了。
 */
@Testcontainers
@SpringBootTest
class DocumentIngestIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recordandgrow")
            .withUsername("recordandgrow")
            .withPassword("recordandgrow");

    private static final Path STORAGE_DIR = createStorageDir();

    private static WireMockServer wireMock;

    @Autowired
    private DocumentIngestService service;

    @Autowired
    private DocumentIngestQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/embeddings"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(embeddingResponse())));
    }

    @AfterAll
    static void stopWireMock() throws IOException {
        if (wireMock != null) {
            wireMock.stop();
        }
        deleteStorage();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("recordandgrow.ai.embedding.base-url", () -> wireMock.baseUrl());
        registry.add("recordandgrow.ai.embedding.api-key", () -> "test-key");
        registry.add("recordandgrow.ai.embedding.batch-size", () -> 1);
        registry.add("recordandgrow.document.queue.mode", () -> "local");
        registry.add("recordandgrow.document.storage-dir", () -> STORAGE_DIR.toString());
        registry.add("recordandgrow.document.reconcile.enabled", () -> false);

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() throws IOException {
        jdbc.execute("DELETE FROM document_chunk");
        jdbc.execute("DELETE FROM document");
        deleteStorage();
    }

    @Test
    void ingestShouldReachReadyAndEmbedEveryChunk() {
        DocumentRegisterResult registered = register("全链路测试文档。Tika 解析、token 切块、向量回填。".repeat(30));

        queue.sendParse(registered.document().getId());
        DocumentResponse response = awaitStatus(registered.document().getId(), "READY");

        assertThat(response.getChunkCount()).isPositive();
        assertThat(response.getTokenCount()).isPositive();
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND embedding IS NULL",
                Integer.class, response.getId());
        assertThat(pending).isZero();

        Integer chunks = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?",
                Integer.class, response.getId());
        assertThat(chunks).isEqualTo(response.getChunkCount());
    }

    @Test
    void reparseShouldBeIdempotent() {
        DocumentRegisterResult registered = register("幂等性测试。同一份内容解析两次，分块结果必须一致。".repeat(20));
        Long id = registered.document().getId();

        queue.sendParse(id);
        DocumentResponse first = awaitStatus(id, "READY");
        List<Map<String, Object>> before = jdbc.queryForList(
                "SELECT chunk_index, content, char_start, char_end FROM document_chunk "
                        + "WHERE document_id = ? ORDER BY chunk_index", id);

        service.ingestParse(id);
        service.ingestEmbed(id);

        List<Map<String, Object>> after = jdbc.queryForList(
                "SELECT chunk_index, content, char_start, char_end FROM document_chunk "
                        + "WHERE document_id = ? ORDER BY chunk_index", id);
        assertThat(after).isEqualTo(before);
        assertThat(service.get(id).getChunkCount()).isEqualTo(first.getChunkCount());
    }

    @Test
    void duplicateUploadShouldReturnSameDocument() {
        String content = "重复上传测试。sha256 相同的文件不该重复解析。".repeat(20);

        DocumentRegisterResult first = register(content);
        DocumentRegisterResult second = register(content);

        assertThat(second.deduplicated()).isTrue();
        assertThat(second.document().getId()).isEqualTo(first.document().getId());
        Integer count = jdbc.queryForObject("SELECT count(*) FROM document", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void chunkOffsetsShouldMatchNormalizedText() {
        DocumentRegisterResult registered = register("偏移测试。每一块的正文必须等于归一化全文的对应区间。".repeat(40));
        Long id = registered.document().getId();

        queue.sendParse(id);
        awaitStatus(id, "READY");

        String normalized = jdbc.queryForObject(
                "SELECT normalized_text FROM document WHERE id = ?", String.class, id);
        List<Map<String, Object>> chunks = jdbc.queryForList(
                "SELECT content, char_start, char_end FROM document_chunk "
                        + "WHERE document_id = ? ORDER BY chunk_index", id);

        assertThat(chunks).isNotEmpty();
        for (Map<String, Object> chunk : chunks) {
            int start = ((Number) chunk.get("char_start")).intValue();
            int end = ((Number) chunk.get("char_end")).intValue();
            String content = (String) chunk.get("content");
            assertThat(normalized.substring(
                    normalized.offsetByCodePoints(0, start),
                    normalized.offsetByCodePoints(0, end))).isEqualTo(content);
        }
    }

    private DocumentRegisterResult register(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return service.register("测试.txt", bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    private DocumentResponse awaitStatus(Long id, String expected) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            DocumentResponse response = service.get(id);
            if (expected.equals(response.getStatus())) {
                return response;
            }
            if ("FAILED".equals(response.getStatus())) {
                fail("文档处理失败: " + response.getErrorMessage());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待状态时被中断");
            }
        }
        fail("等待文档状态超时: id=" + id + ", expected=" + expected);
        return null;
    }

    private static String embeddingResponse() {
        StringBuilder json = new StringBuilder("{\"data\":[{\"index\":0,\"embedding\":[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("0.01");
        }
        json.append("]}],\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}");
        return json.toString();
    }

    private static Path createStorageDir() {
        try {
            return Files.createTempDirectory("recordandgrow-documents");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试存储目录", e);
        }
    }

    private static void deleteStorage() throws IOException {
        if (!Files.exists(STORAGE_DIR)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(STORAGE_DIR)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(STORAGE_DIR))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 清理失败不影响断言
                        }
                    });
        }
    }
}
