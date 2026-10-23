package com.recordandgrow.retrieval.document;

import com.recordandgrow.ai.rerank.RerankClient;
import com.recordandgrow.ai.rerank.RerankScore;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.config.FusionProperties;
import com.recordandgrow.config.RerankProperties;
import com.recordandgrow.document.keyword.KeywordDoc;
import com.recordandgrow.document.keyword.KeywordHit;
import com.recordandgrow.document.keyword.KeywordSearcher;
import com.recordandgrow.document.keyword.KeywordStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 融合入口的开关语义：
 * 关闭 / 没有关键词通道 / 向量侧降级 / 关键词失败 四种情况都必须**原样返回向量结果**。
 */
class DocumentFusionServiceTest {

    private DocumentRetriever vectorRetriever;
    private KeywordSearcher keywordSearcher;
    private RerankClient rerankClient;
    private FusionProperties fusion;
    private RerankProperties rerank;
    private ChatProperties chat;

    @BeforeEach
    void setUp() {
        vectorRetriever = mock(DocumentRetriever.class);
        keywordSearcher = mock(KeywordSearcher.class);
        rerankClient = mock(RerankClient.class);
        fusion = new FusionProperties();
        rerank = new RerankProperties();
        chat = new ChatProperties();
        chat.getDocuments().setTopK(8);
        chat.getDocuments().setMaxPerDocument(2);
    }

    @Test
    void disabledForwardsVectorResultUntouched() {
        DocumentRetrievalResult vector = vectorResult(1L, 2L);
        when(vectorRetriever.retrieve(any())).thenReturn(vector);

        DocumentRetrievalResult result = service(false).retrieve("q");

        assertSame(vector, result);
        verify(keywordSearcher, never()).search(any());
    }

    @Test
    void noKeywordBeanForwardsVectorResult() {
        DocumentRetrievalResult vector = vectorResult(1L);
        when(vectorRetriever.retrieve(any())).thenReturn(vector);

        DocumentFusionService service = new DocumentFusionService(
                vectorRetriever, provider(null), provider(null), fusion, rerank, chat);

        assertSame(vector, service.retrieve("q"));
    }

    @Test
    void degradedVectorResultIsNotMaskedByKeywordHits() {
        DocumentRetrievalResult degraded = DocumentRetrievalResult.degraded(
                DocumentDegradeReason.TIMEOUT, 12);
        when(vectorRetriever.retrieve(any())).thenReturn(degraded);

        DocumentRetrievalResult result = service(true).retrieve("q");

        assertSame(degraded, result);
        verify(keywordSearcher, never()).search(any());
    }

    @Test
    void keywordFailureFallsBackToVectorOnly() {
        DocumentRetrievalResult vector = vectorResult(1L);
        when(vectorRetriever.retrieve(any())).thenReturn(vector);
        when(keywordSearcher.search(any())).thenThrow(new KeywordStoreException("search", new RuntimeException("es down")));

        assertSame(vector, service(true).retrieve("q"));
    }

    @Test
    void fusesTwoChannelsAndDeduplicates() {
        when(vectorRetriever.retrieve(any())).thenReturn(vectorResult(1L, 2L));
        when(keywordSearcher.search(any())).thenReturn(List.of(
                keywordHit(2L, 10.0), keywordHit(3L, 5.0)));

        DocumentRetrievalResult result = service(true).retrieve("q");

        assertEquals(3, result.hits().size(), "1、2、3 去重后是 3 条");
        assertEquals(2L, result.hits().get(0).chunkId(), "2 号两路都在，融合后排第一");
        assertEquals(1L, result.hits().get(1).chunkId());
        assertEquals(3L, result.hits().get(2).chunkId());
    }

    @Test
    void rerankReordersCandidatesAndAttachesScores() {
        when(vectorRetriever.retrieve(any())).thenReturn(vectorResult(1L, 2L));
        when(keywordSearcher.search(any())).thenReturn(List.of(keywordHit(3L, 9.0)));
        rerank.setEnabled(true);
        when(rerankClient.isConfigured()).thenReturn(true);
        // 候选顺序是 [1, 3, 2]（RRF 后 1 与 3 同分，稳定序保持先出现的 1）
        when(rerankClient.rerank(any(), any(), anyInt())).thenReturn(List.of(
                new RerankScore(2, 0.9), new RerankScore(0, 0.5), new RerankScore(1, 0.1)));

        DocumentRetrievalResult result = service(true).retrieve("q");

        assertEquals(3, result.hits().size());
        assertEquals(2L, result.hits().get(0).chunkId(), "精排把候选下标 2 提到第一");
        assertEquals(1L, result.hits().get(1).chunkId());
        assertEquals(3L, result.hits().get(2).chunkId());
        assertEquals(0.9, result.hits().get(0).score(), 1e-9);
    }

    @Test
    void evidenceGateDropsWholeBatchBelowThreshold() {
        when(vectorRetriever.retrieve(any())).thenReturn(vectorResult(1L, 2L));
        when(keywordSearcher.search(any())).thenReturn(List.of(keywordHit(3L, 9.0)));
        rerank.setEnabled(true);
        rerank.setMinScore(0.2);
        when(rerankClient.isConfigured()).thenReturn(true);
        when(rerankClient.rerank(any(), any(), anyInt())).thenReturn(List.of(
                new RerankScore(0, 0.05), new RerankScore(1, 0.04), new RerankScore(2, 0.03)));

        DocumentRetrievalResult result = service(true).retrieve("q");

        assertEquals(0, result.hits().size(), "整批最高分低于阈值 → 整批丢");
        assertEquals(true, result.searched(), "仍然算检索过，不是未检索");
    }

    @Test
    void rerankFailureKeepsRrfOrder() {
        when(vectorRetriever.retrieve(any())).thenReturn(vectorResult(1L, 2L));
        when(keywordSearcher.search(any())).thenReturn(List.of(keywordHit(3L, 9.0)));
        rerank.setEnabled(true);
        when(rerankClient.isConfigured()).thenReturn(true);
        when(rerankClient.rerank(any(), any(), anyInt()))
                .thenThrow(new com.recordandgrow.ai.AiException(
                        com.recordandgrow.ai.AiErrorType.TIMEOUT, "rerank timeout"));

        DocumentRetrievalResult result = service(true).retrieve("q");

        assertEquals(3, result.hits().size());
        assertEquals(1L, result.hits().get(0).chunkId(), "失败时保留 RRF 原序");
    }

    private DocumentFusionService service(boolean enabled) {
        fusion.setEnabled(enabled);
        return new DocumentFusionService(vectorRetriever, provider(keywordSearcher), provider(rerankClient),
                fusion, rerank, chat);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static DocumentRetrievalResult vectorResult(long... chunkIds) {
        List<DocumentHit> hits = java.util.Arrays.stream(chunkIds)
                .mapToObj(id -> new DocumentHit(id, id, "t", null, "c", 0.9 - id * 0.01, null, null, 0))
                .toList();
        return DocumentRetrievalResult.of(hits, 0.9);
    }

    private static KeywordHit keywordHit(long chunkId, double score) {
        return new KeywordHit(new KeywordDoc(chunkId, chunkId, 0, "t", null, "c"), score);
    }
}
