package com.recordandgrow.document;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.NoopAiCallObserver;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.ai.qwen.QwenEmbeddingClient;
import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.chunk.Chunker;
import com.recordandgrow.document.parse.DocumentParser;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import com.recordandgrow.document.storage.DocumentStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * embedding 回填的批量与对位：真实 QwenEmbeddingClient 打 WireMock，
 * 不连真实供应商。
 *
 * <p>验证三件事：25 块拆成 10/10/5 三次调用；响应乱序时写入顺序仍与分块一致；
 * 维度不符时整批不写、异常上抛；429 会重试。
 */
@ExtendWith(MockitoExtension.class)
class DocumentEmbeddingWireMockTest {

    private static WireMockServer server;

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper chunkMapper;
    @Mock
    private DocumentChunkWriter chunkWriter;
    @Mock
    private DocumentStorage storage;

    private DocumentIngestService service;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        AiProperties aiProperties = new AiProperties();
        AiProperties.Embedding embedding = aiProperties.getEmbedding();
        embedding.setBaseUrl("http://localhost:" + server.port());
        embedding.setApiKey("test-key");
        embedding.setDimensions(1024);
        embedding.setBatchSize(10);
        embedding.setMaxAttempts(2);
        embedding.setTimeout(Duration.ofSeconds(5));
        embedding.setConnectTimeout(Duration.ofSeconds(2));

        QwenEmbeddingClient client = new QwenEmbeddingClient(
                embedding, new ObjectMapper(), new NoopAiCallObserver());
        service = new DocumentIngestService(documentMapper, chunkMapper, chunkWriter, storage,
                org.mockito.Mockito.mock(DocumentParser.class),
                org.mockito.Mockito.mock(Chunker.class),
                client, org.mockito.Mockito.mock(DocumentIngestQueue.class),
                new DocumentProperties(), aiProperties,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    @Test
    void shouldSplit25ChunksIntoThreeBatches() {
        stubBatch("c0", 0, 10);
        stubBatch("c10", 10, 10);
        stubBatch("c20", 20, 5);

        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));
        when(chunkMapper.selectPendingBatch(1L, 10)).thenReturn(
                batch(0, 10), batch(10, 10), batch(20, 5), List.of());
        when(chunkMapper.countByDocumentId(1L)).thenReturn(25);
        when(chunkMapper.sumTokenCount(1L)).thenReturn(1000);

        service.ingestEmbed(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<float[]>> vectorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkWriter, times(3)).writeBatch(
                chunksCaptor.capture(), vectorsCaptor.capture(), eq("text-embedding-v4"));

        assertThat(chunksCaptor.getAllValues()).extracting(List::size).containsExactly(10, 10, 5);
        verify(documentMapper, times(3)).touchProgress(1L);
        verify(documentMapper).markReady(1L, 25, 1000);
    }

    @Test
    void vectorsShouldStayAlignedWithChunkOrder() {
        stubBatch("c0", 0, 10);
        stubBatch("c10", 10, 10);
        stubBatch("c20", 20, 5);

        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));
        when(chunkMapper.selectPendingBatch(1L, 10)).thenReturn(
                batch(0, 10), batch(10, 10), batch(20, 5), List.of());
        when(chunkMapper.countByDocumentId(1L)).thenReturn(25);
        when(chunkMapper.sumTokenCount(1L)).thenReturn(1000);

        service.ingestEmbed(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<float[]>> vectorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkWriter, times(3)).writeBatch(anyList(), vectorsCaptor.capture(), eq("text-embedding-v4"));

        List<List<float[]>> all = vectorsCaptor.getAllValues();
        // 假响应按 index 倒序返回，客户端必须重排：第 i 个向量首维 = base + i
        assertThat(all.get(0).get(0)[0]).isEqualTo(0f);
        assertThat(all.get(0).get(9)[0]).isEqualTo(9f);
        assertThat(all.get(1).get(0)[0]).isEqualTo(10f);
        assertThat(all.get(2).get(0)[0]).isEqualTo(20f);
        assertThat(all.get(2).get(4)[0]).isEqualTo(24f);
    }

    @Test
    void rateLimitedShouldBeRetried() {
        server.stubFor(post(urlEqualTo("/embeddings"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("second")
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"rate limited\"}}")));
        server.stubFor(post(urlEqualTo("/embeddings"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(okJson(response(0, 1, 1024))));

        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));
        when(chunkMapper.selectPendingBatch(1L, 10)).thenReturn(
                List.<DocumentChunk>of(chunk(0, "c0")), List.of());
        when(chunkMapper.countByDocumentId(1L)).thenReturn(1);
        when(chunkMapper.sumTokenCount(1L)).thenReturn(10);

        service.ingestEmbed(1L);

        verify(chunkWriter).writeBatch(anyList(), anyList(), eq("text-embedding-v4"));
        assertThat(server.getAllServeEvents()).hasSize(2);
    }

    @Test
    void dimensionMismatchShouldNotWriteAnything() {
        // 返回 512 维，客户端应抛 BAD_RESPONSE，整批不写
        server.stubFor(post(urlEqualTo("/embeddings"))
                .willReturn(okJson(response(0, 1, 512))));

        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));
        when(chunkMapper.selectPendingBatch(1L, 10)).thenReturn(
                List.<DocumentChunk>of(chunk(0, "c0")), List.of());

        assertThatThrownBy(() -> service.ingestEmbed(1L)).isInstanceOf(AiException.class);

        verify(chunkWriter, never()).writeBatch(anyList(), anyList(), eq("text-embedding-v4"));
        verify(documentMapper, never()).markReady(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    private void stubBatch(String firstInput, int base, int count) {
        server.stubFor(post(urlEqualTo("/embeddings"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo(firstInput)))
                .willReturn(okJson(response(base, count, 1024))));
    }

    /** 构造响应：data 按 index 倒序，第 i 条的 embedding 首维 = base + i。 */
    private static String response(int base, int count, int dimensions) {
        StringBuilder json = new StringBuilder();
        json.append("{\"data\":[");
        for (int i = count - 1; i >= 0; i--) {
            if (i < count - 1) {
                json.append(',');
            }
            json.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < dimensions; d++) {
                if (d > 0) {
                    json.append(',');
                }
                json.append(d == 0 ? base + i : 0);
            }
            json.append("]}");
        }
        json.append("],\"usage\":{\"prompt_tokens\":10,\"total_tokens\":10}}");
        return json.toString();
    }

    private static List<DocumentChunk> batch(int from, int size) {
        List<DocumentChunk> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunks.add(chunk(from + i, "c" + (from + i)));
        }
        return chunks;
    }

    private static DocumentChunk chunk(int index, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId((long) (index + 1));
        chunk.setDocumentId(1L);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setTokenCount(10);
        return chunk;
    }

    private static Document document(Long id, String status) {
        Document document = new Document();
        document.setId(id);
        document.setStatus(status);
        return document;
    }
}
