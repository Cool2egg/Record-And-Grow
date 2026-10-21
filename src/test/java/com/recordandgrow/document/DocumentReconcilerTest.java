package com.recordandgrow.document;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账任务：只补发、封顶后判失败、单条失败不影响其它文档。
 */
class DocumentReconcilerTest {

    private DocumentMapper documentMapper;
    private DocumentIngestService service;
    private DocumentIngestQueue queue;
    private DocumentProperties properties;
    private DocumentReconciler reconciler;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        service = mock(DocumentIngestService.class);
        queue = mock(DocumentIngestQueue.class);
        properties = new DocumentProperties();
        reconciler = new DocumentReconciler(documentMapper, service, queue, properties);
    }

    @Test
    void pendingDocumentShouldBeResentAsParse() {
        when(documentMapper.selectStale(anyLong())).thenReturn(List.of(document(1L, "PENDING", 0)));

        int resent = reconciler.reconcile();

        assertThat(resent).isEqualTo(1);
        verify(documentMapper).bumpRetryCount(1L);
        verify(queue).sendParse(1L);
        verify(queue, never()).sendEmbed(anyLong());
    }

    @Test
    void embeddingDocumentShouldBeResentAsEmbed() {
        when(documentMapper.selectStale(anyLong())).thenReturn(List.of(document(2L, "EMBEDDING", 1)));

        reconciler.reconcile();

        verify(documentMapper).bumpRetryCount(2L);
        verify(queue).sendEmbed(2L);
    }

    @Test
    void documentOverRetryLimitShouldFailInsteadOfResend() {
        when(documentMapper.selectStale(anyLong())).thenReturn(List.of(document(3L, "PENDING", 3)));

        int resent = reconciler.reconcile();

        assertThat(resent).isZero();
        verify(service).markFailed(eq(3L), anyString());
        verify(queue, never()).sendParse(3L);
        verify(documentMapper, never()).bumpRetryCount(3L);
    }

    @Test
    void sendFailureShouldNotStopOtherDocuments() {
        when(documentMapper.selectStale(anyLong())).thenReturn(List.of(
                document(4L, "PENDING", 0), document(5L, "PENDING", 0)));
        doThrow(new IllegalStateException("broker down")).when(queue).sendParse(4L);

        int resent = reconciler.reconcile();

        assertThat(resent).isEqualTo(1);
        verify(queue).sendParse(5L);
    }

    @Test
    void nothingStaleShouldSendNothing() {
        when(documentMapper.selectStale(anyLong())).thenReturn(List.of());

        assertThat(reconciler.reconcile()).isZero();
        verify(queue, never()).sendParse(anyLong());
    }

    private static Document document(Long id, String status, int retryCount) {
        Document document = new Document();
        document.setId(id);
        document.setStatus(status);
        document.setRetryCount(retryCount);
        return document;
    }
}
