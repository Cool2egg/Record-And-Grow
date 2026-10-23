package com.recordandgrow.retrieval;

import com.recordandgrow.agent.route.QueryRoute;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.config.FusionProperties;
import com.recordandgrow.config.RerankProperties;
import com.recordandgrow.document.keyword.KeywordSearcher;
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.document.DocumentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 并行编排的契约测试：只关心「与串行实现等价」的三件事。
 *
 * <ol>
 *   <li>两路结果都被取回，且带各自的耗时；</li>
 *   <li>异常原样抛出（不被 {@code CompletionException} 包住），两路同时失败时抛时间线的那个；</li>
 *   <li>MDC 里的 {@code requestId} 能进工作线程，且不污染调用线程。</li>
 * </ol>
 */
class RetrievalEngineTest {

    private TimelineRetriever timelineRetriever;
    private DocumentRetriever documentRetriever;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        timelineRetriever = mock(TimelineRetriever.class);
        documentRetriever = mock(DocumentRetriever.class);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        MDC.clear();
    }

    @Test
    void returnsBothResultsWithTheirOwnTimings() {
        RetrievalResult timeline = new RetrievalResult(List.of(), null, 3, 2, false, List.of("go"), false);
        DocumentRetrievalResult documents = DocumentRetrievalResult.notSearched();
        when(timelineRetriever.retrieve(any(), any())).thenReturn(timeline);
        when(documentRetriever.retrieve(any())).thenReturn(documents);

        RetrievalBundle bundle = engine(pool).retrieve("我昨天做了什么？", null, List.of("go"));

        assertSame(timeline, bundle.timeline());
        assertSame(documents, bundle.documents());
        assertTrue(bundle.timelineMs() >= 0);
        assertTrue(bundle.documentMs() >= 0);
    }

    @Test
    void timelineFailureIsRethrownUnwrapped() {
        IllegalStateException boom = new IllegalStateException("timeline down");
        when(timelineRetriever.retrieve(any(), any())).thenThrow(boom);
        when(documentRetriever.retrieve(any())).thenReturn(DocumentRetrievalResult.notSearched());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> engine(Runnable::run).retrieve("q", null, List.of()));

        assertSame(boom, thrown, "必须是原始异常实例，不能是 CompletionException");
        verify(documentRetriever).retrieve(any());
    }

    @Test
    void timelineExceptionWinsWhenBothFail() {
        IllegalStateException timelineBoom = new IllegalStateException("timeline down");
        IllegalStateException documentBoom = new IllegalStateException("document down");
        when(timelineRetriever.retrieve(any(), any())).thenThrow(timelineBoom);
        when(documentRetriever.retrieve(any())).thenThrow(documentBoom);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> engine(Runnable::run).retrieve("q", null, List.of()));

        assertSame(timelineBoom, thrown, "两路同时失败时与串行顺序一致：抛时间线的异常");
    }

    @Test
    void documentFailurePropagatesWhenTimelineSucceeds() {
        IllegalStateException boom = new IllegalStateException("document down");
        when(timelineRetriever.retrieve(any(), any()))
                .thenReturn(new RetrievalResult(List.of(), null, 0, 0, false, List.of(), false));
        when(documentRetriever.retrieve(any())).thenThrow(boom);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> engine(pool).retrieve("q", null, List.of()));

        assertSame(boom, thrown);
    }

    @Test
    void propagatesRequestIdIntoWorkerThreadAndRestoresCaller() {
        AtomicReference<String> seenOnWorker = new AtomicReference<>("NOT_CALLED");
        AtomicReference<String> workerName = new AtomicReference<>();
        when(timelineRetriever.retrieve(any(), any())).thenAnswer(invocation -> {
            seenOnWorker.set(MDC.get("requestId"));
            workerName.set(Thread.currentThread().getName());
            return new RetrievalResult(List.of(), null, 0, 0, false, List.of(), false);
        });
        when(documentRetriever.retrieve(any())).thenReturn(DocumentRetrievalResult.notSearched());

        MDC.put("requestId", "req-42");
        try {
            engine(pool).retrieve("q", null, List.of());
            assertEquals("req-42", seenOnWorker.get());
            assertTrue(workerName.get().startsWith("pool-"));
        } finally {
            MDC.remove("requestId");
        }

        // 工作线程跑完后，调用线程自己的 MDC 不能被子线程的还原逻辑改掉
        assertNull(MDC.get("requestId"));
    }

    @Test
    void directRouteSkipsBothRetrievers() {
        RetrievalBundle bundle = engine(Runnable::run).retrieve("你好", null, List.of(), QueryRoute.DIRECT);

        assertTrue(bundle.timeline().items().isEmpty());
        assertFalse(bundle.documents().searched());
        assertEquals(0, bundle.timelineMs());
        verifyNoInteractions(timelineRetriever, documentRetriever);
    }

    @Test
    void documentRouteSkipsTimelineRetrieval() {
        when(documentRetriever.retrieve(any())).thenReturn(DocumentRetrievalResult.notSearched());

        RetrievalBundle bundle = engine(Runnable::run).retrieve("q", null, List.of("go"), QueryRoute.DOCUMENT);

        assertTrue(bundle.timeline().items().isEmpty());
        assertEquals(0, bundle.timeline().matched());
        assertEquals(1, bundle.timeline().keywords().size(), "关键词仍然透传，供 Prompt 展示");
        verifyNoInteractions(timelineRetriever);
    }

    @Test
    void timelineRouteSkipsDocumentRetrieval() {
        when(timelineRetriever.retrieve(any(), any()))
                .thenReturn(new RetrievalResult(List.of(), null, 0, 0, false, List.of(), false));

        RetrievalBundle bundle = engine(Runnable::run).retrieve("q", null, List.of(), QueryRoute.TIMELINE);

        assertFalse(bundle.documents().searched());
        verifyNoInteractions(documentRetriever);
    }

    private RetrievalEngine engine(Executor executor) {
        ObjectProvider<KeywordSearcher> noKeyword = mock(ObjectProvider.class);
        DocumentFusionService documents = new DocumentFusionService(
                documentRetriever, noKeyword, mock(ObjectProvider.class),
                new FusionProperties(), new RerankProperties(), new ChatProperties());
        return new RetrievalEngine(timelineRetriever, documents, executor);
    }
}
