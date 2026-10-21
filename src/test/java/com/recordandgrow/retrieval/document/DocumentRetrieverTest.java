package com.recordandgrow.retrieval.document;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.document.DocumentChunkMapper;
import com.recordandgrow.document.DocumentMapper;
import com.recordandgrow.document.DocumentSearchRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentRetriever} 的纯单测：mock mapper + fake embedding，不依赖 Spring 与数据库。
 *
 * <p>覆盖设计稿 15.1 的必测边界：三态、10 种 {@code AiErrorType} 穷举、阈值、配额、
 * 相邻块去重与顺延补位、平局顺序、topScore 取过滤前最高分、SQL 异常上抛、确定性。
 */
class DocumentRetrieverTest {

    private static final String QUESTION = "Docker 镜像加速怎么配？";
    private static final String MODEL = "text-embedding-v4";
    private static final Map<AiErrorType, DocumentDegradeReason> EXPECTED_REASONS = Map.of(
            AiErrorType.TIMEOUT, DocumentDegradeReason.TIMEOUT,
            AiErrorType.RATE_LIMITED, DocumentDegradeReason.RATE_LIMITED,
            AiErrorType.AUTHENTICATION, DocumentDegradeReason.AUTH,
            AiErrorType.INSUFFICIENT_BALANCE, DocumentDegradeReason.AUTH,
            AiErrorType.UPSTREAM_ERROR, DocumentDegradeReason.UPSTREAM,
            AiErrorType.NETWORK, DocumentDegradeReason.UPSTREAM,
            AiErrorType.INVALID_REQUEST, DocumentDegradeReason.UPSTREAM,
            AiErrorType.STREAM_INTERRUPTED, DocumentDegradeReason.UPSTREAM,
            AiErrorType.BAD_RESPONSE, DocumentDegradeReason.BAD_RESPONSE,
            AiErrorType.NOT_CONFIGURED, DocumentDegradeReason.NOT_CONFIGURED);

    private ChatProperties properties;
    private DocumentMapper documentMapper;
    private DocumentChunkMapper chunkMapper;
    private FakeEmbeddingClient embeddingClient;
    private DocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        properties = new ChatProperties();
        documentMapper = mock(DocumentMapper.class);
        chunkMapper = mock(DocumentChunkMapper.class);
        embeddingClient = new FakeEmbeddingClient();
        retriever = new DocumentRetriever(properties, new AiProperties(), documentMapper, chunkMapper,
                embeddingClient);
        when(documentMapper.existsReady()).thenReturn(true);
    }

    @Test
    void disabledSwitchSkipsEverything() {
        properties.getDocuments().setEnabled(false);

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.searched()).isFalse();
        assertThat(result.degraded()).isFalse();
        verifyNoInteractions(documentMapper, chunkMapper);
        assertThat(embeddingClient.calls).isZero();
    }

    @Test
    void noReadyDocumentDoesNotCallEmbedding() {
        when(documentMapper.existsReady()).thenReturn(false);

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.searched()).isFalse();
        assertThat(result.degraded()).isFalse();
        assertThat(result.hits()).isEmpty();
        assertThat(embeddingClient.calls).as("空库不该花一次 embedding").isZero();
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void missingKeyDegradesWithoutTouchingSql() {
        embeddingClient.configured = false;

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.searched()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradeReason()).isEqualTo(DocumentDegradeReason.NOT_CONFIGURED);
        assertThat(embeddingClient.calls).isZero();
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void queryEmbeddingExceptionDegradesButKeepsTimelineUsable() {
        embeddingClient.failure = new AiException(AiErrorType.TIMEOUT, "timeout");

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.searched()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradeReason()).isEqualTo(DocumentDegradeReason.TIMEOUT);
        verifyNoInteractions(chunkMapper);
    }

    @ParameterizedTest
    @EnumSource(AiErrorType.class)
    void everyAiErrorTypeMapsToItsDegradeReason(AiErrorType type) {
        embeddingClient.failure = new AiException(type, "boom");

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradeReason()).isEqualTo(EXPECTED_REASONS.get(type));
    }

    @Test
    void wrongConfiguredDimensionsDegradeBeforeCallingEmbedding() {
        embeddingClient.dimensions = 512;

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.degradeReason()).isEqualTo(DocumentDegradeReason.DIMENSION);
        assertThat(embeddingClient.calls).isZero();
    }

    @Test
    void wrongVectorLengthDegrades() {
        embeddingClient.vector = new float[512];

        assertThat(retriever.retrieve(QUESTION).degradeReason()).isEqualTo(DocumentDegradeReason.DIMENSION);
    }

    @Test
    void multipleVectorsDegradeAsBadResponse() {
        embeddingClient.vectors = List.of(new float[1024], new float[1024]);

        assertThat(retriever.retrieve(QUESTION).degradeReason()).isEqualTo(DocumentDegradeReason.BAD_RESPONSE);
    }

    @Test
    void zeroResultsAreSearchedWithNullTopScore() {
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of());

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.searched()).isTrue();
        assertThat(result.degraded()).isFalse();
        assertThat(result.hits()).isEmpty();
        assertThat(result.topScore()).isNull();
    }

    @Test
    void filtersBelowThresholdButKeepsPreFilterTopScore() {
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of(
                row(1, 10, 0, 0.10),   // similarity 0.90
                row(2, 10, 3, 0.74),   // similarity 0.26 → 刚好及格
                row(3, 10, 6, 0.80))); // 0.20 → 过滤

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.hits()).extracting(DocumentHit::chunkId).containsExactly(1L, 2L);
        assertThat(result.topScore()).as("topScore 是过滤前的最高分").isCloseTo(0.90, within());
    }

    @Test
    void allBelowThresholdStillReportsTopScore() {
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of(
                row(1, 10, 0, 0.90),
                row(2, 10, 1, 0.85)));

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.searched()).isTrue();
        assertThat(result.hits()).isEmpty();
        assertThat(result.topScore()).as("全灭时也要能看出差多少").isCloseTo(0.15, within());
    }

    @Test
    void keepsAtMostTwoChunksPerDocument() {
        properties.getDocuments().setMaxPerDocument(2);
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of(
                row(1, 10, 0, 0.10),
                row(2, 10, 3, 0.20),
                row(3, 10, 6, 0.30),
                row(4, 20, 0, 0.40)));

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.hits()).extracting(DocumentHit::chunkId).containsExactly(1L, 2L, 4L);
    }

    @Test
    void adjacentChunksKeepOnlyHigherScoreAndBackfill() {
        // 文档 10 的 0 和 1 相邻：只留 0（分更高），从后面的候选顺延补位
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of(
                row(1, 10, 0, 0.10),
                row(2, 10, 1, 0.20),
                row(3, 10, 3, 0.30),
                row(4, 20, 0, 0.40)));

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.hits()).extracting(DocumentHit::chunkId).containsExactly(1L, 3L, 4L);
    }

    @Test
    void ordersByScoreThenDocumentChunkAndChunkId() {
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of(
                row(9, 20, 0, 0.30),
                row(8, 10, 5, 0.30),
                row(7, 10, 0, 0.30),
                row(6, 5, 0, 0.20)));

        DocumentRetrievalResult result = retriever.retrieve(QUESTION);

        assertThat(result.hits()).extracting(DocumentHit::chunkId)
                .containsExactly(6L, 7L, 8L, 9L);
    }

    @Test
    void capsHitsAtTopK() {
        properties.getDocuments().setTopK(3);
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of(
                row(1, 10, 0, 0.10),
                row(2, 20, 0, 0.20),
                row(3, 30, 0, 0.30),
                row(4, 40, 0, 0.40)));

        assertThat(retriever.retrieve(QUESTION).hits()).hasSize(3);
    }

    @Test
    void sqlFailurePropagatesInsteadOfPretendingNoHit() {
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> retriever.retrieve(QUESTION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameInputProducesSameOrderTwice() {
        List<DocumentSearchRow> rows = new ArrayList<>(List.of(
                row(5, 10, 4, 0.30),
                row(4, 10, 0, 0.30),
                row(3, 20, 1, 0.30),
                row(2, 20, 3, 0.10),
                row(1, 30, 0, 0.50)));
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(rows);

        List<Long> first = retriever.retrieve(QUESTION).hits().stream().map(DocumentHit::chunkId).toList();
        List<Long> second = retriever.retrieve(QUESTION).hits().stream().map(DocumentHit::chunkId).toList();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void passesScanLimitAndConfiguredModelToSql() {
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of());

        retriever.retrieve(QUESTION);

        verify(chunkMapper).search(anyString(), eq(MODEL), eq(24));
    }

    @Test
    void usesPerQueryTimeoutAndSingleAttempt() {
        when(chunkMapper.search(anyString(), eq(MODEL), anyInt())).thenReturn(List.of());
        retriever.retrieve(QUESTION);

        assertThat(embeddingClient.lastTimeout).isEqualTo(Duration.ofSeconds(8));
        assertThat(embeddingClient.lastMaxAttempts).isEqualTo(1);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }

    private static DocumentSearchRow row(long chunkId, long documentId, int chunkIndex, double distance) {
        DocumentSearchRow row = new DocumentSearchRow();
        row.setChunkId(chunkId);
        row.setDocumentId(documentId);
        row.setChunkIndex(chunkIndex);
        row.setTitle("测试文档");
        row.setHeading("部署 > 镜像加速");
        row.setContent("块正文 " + chunkId);
        row.setCharStart(0);
        row.setCharEnd(5);
        row.setTokenCount(5);
        row.setDistance(distance);
        return row;
    }

    /** 可配置返回值/异常/维度的 fake；不覆写按次重载，顺带验证接口默认方法可用。 */
    private static final class FakeEmbeddingClient implements EmbeddingClient {

        private boolean configured = true;
        private int dimensions = 1024;
        private float[] vector = new float[1024];
        private List<float[]> vectors;
        private AiException failure;
        private int calls;
        private Duration lastTimeout;
        private int lastMaxAttempts;

        @Override
        public List<float[]> embed(List<String> texts) {
            return embed(texts, Duration.ofSeconds(30), 2);
        }

        @Override
        public List<float[]> embed(List<String> texts, Duration timeout, int maxAttempts) {
            calls++;
            lastTimeout = timeout;
            lastMaxAttempts = maxAttempts;
            if (failure != null) {
                throw failure;
            }
            if (vectors != null) {
                return vectors;
            }
            return List.of(vector);
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }
    }
}
