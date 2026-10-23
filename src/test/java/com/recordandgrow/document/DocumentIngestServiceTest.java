package com.recordandgrow.document;

import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.config.AiProperties;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.chunk.Chunk;
import com.recordandgrow.document.chunk.ChunkResult;
import com.recordandgrow.document.chunk.Chunker;
import com.recordandgrow.document.dto.DocumentRegisterResult;
import com.recordandgrow.document.parse.DocumentParser;
import com.recordandgrow.document.parse.ParsedDocument;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import com.recordandgrow.document.storage.DocumentStorage;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 入库编排：注册、状态机、幂等、断点续跑、并发守卫。
 *
 * <p>全部 mock，不连库、不连 MQ；真实 SQL 语义交给集成测试。
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestServiceTest {

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper chunkMapper;
    @Mock
    private DocumentChunkWriter chunkWriter;
    @Mock
    private DocumentStorage storage;
    @Mock
    private DocumentParser parser;
    @Mock
    private Chunker chunker;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private DocumentIngestQueue queue;

    @TempDir
    Path tempDir;

    private DocumentProperties properties;
    private AiProperties aiProperties;
    private DocumentIngestService service;

    @BeforeEach
    void setUp() {
        properties = new DocumentProperties();
        aiProperties = new AiProperties();
        service = new DocumentIngestService(documentMapper, chunkMapper, chunkWriter, storage,
                parser, chunker, embeddingClient, queue, properties, aiProperties,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    // ---------- 注册 ----------

    @Test
    void registerShouldCreatePendingDocument() {
        when(embeddingClient.isConfigured()).thenReturn(true);
        when(documentMapper.selectActiveBySha256(anyString())).thenReturn(null);
        when(parser.detectMime(any())).thenReturn("text/plain");
        when(storage.store(any(), anyString(), eq("txt")))
                .thenReturn(new DocumentStorage.StoredFile("data/documents/x.txt", 11));
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Document.class).setId(1L);
            return 1;
        });

        DocumentRegisterResult result = service.register("sample.txt", 11, () -> stream("hello world"));

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.sendParse()).isTrue();
        assertThat(result.document().getId()).isEqualTo(1L);
        assertThat(result.document().getStatus()).isEqualTo("PENDING");
        assertThat(result.document().getTitle()).isEqualTo("sample");
        verify(queue, never()).sendParse(anyLong());
    }

    @Test
    void registerWithoutEmbeddingKeyShouldFailBeforeTouchingStorage() {
        when(embeddingClient.isConfigured()).thenReturn(false);

        BusinessException e = catchThrowableOfType(
                () -> service.register("a.txt", 5, () -> stream("hello")), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
        verifyNoInteractions(storage, documentMapper);
    }

    @Test
    void registerShouldRejectUnsupportedExtension() {
        when(embeddingClient.isConfigured()).thenReturn(true);

        BusinessException e = catchThrowableOfType(
                () -> service.register("report.docx", 5, () -> stream("hello")), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(storage);
    }

    @Test
    void registerShouldRejectOversizedFile() {
        when(embeddingClient.isConfigured()).thenReturn(true);
        properties.setMaxFileSize(DataSize.ofBytes(5));

        BusinessException e = catchThrowableOfType(
                () -> service.register("a.txt", 10, () -> stream("hello")), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void registerDuplicateShouldReturnExistingAndNotStoreAgain() {
        when(embeddingClient.isConfigured()).thenReturn(true);
        when(documentMapper.selectActiveBySha256(anyString())).thenReturn(document(7L, "PENDING"));

        DocumentRegisterResult result = service.register("a.txt", 5, () -> stream("hello"));

        assertThat(result.deduplicated()).isTrue();
        assertThat(result.sendParse()).isTrue();
        assertThat(result.document().getId()).isEqualTo(7L);
        verifyNoInteractions(storage);
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void registerDuplicateReadyShouldNotResendParse() {
        when(embeddingClient.isConfigured()).thenReturn(true);
        when(documentMapper.selectActiveBySha256(anyString())).thenReturn(document(7L, "READY"));

        DocumentRegisterResult result = service.register("a.txt", 5, () -> stream("hello"));

        assertThat(result.deduplicated()).isTrue();
        assertThat(result.sendParse()).isFalse();
    }

    // ---------- parse ----------

    @Test
    void ingestParseShouldInsertChunksAndSendEmbed() {
        Document document = document(1L, "PENDING");
        document.setNormalizedText("归一化后的正文。");
        when(documentMapper.selectById(1L)).thenReturn(document);
        when(documentMapper.markParsing(1L)).thenReturn(1);
        when(chunker.chunk("归一化后的正文。")).thenReturn(new ChunkResult(List.of(
                chunkRecord(0, "归一化后的正文。", null), chunkRecord(1, "第二块。", null)), false));
        when(documentMapper.markEmbedding(eq(1L), eq(2), anyInt(), anyInt(), eq(false))).thenReturn(1);

        service.ingestParse(1L);

        verify(chunkMapper).deleteByDocumentId(1L);
        verify(chunkMapper, times(2)).insert(any(DocumentChunk.class));
        verify(documentMapper).markEmbedding(eq(1L), eq(2), anyInt(), anyInt(), eq(false));
        verify(queue).sendEmbed(1L);
        verifyNoInteractions(parser);
    }

    @Test
    void ingestParseShouldParseFileWhenNormalizedTextMissing() throws IOException {
        Path file = tempDir.resolve("raw.txt");
        Files.writeString(file, "原始文本");
        Document document = document(1L, "PENDING");
        document.setStoragePath(file.toString());
        when(documentMapper.selectById(1L)).thenReturn(document);
        when(documentMapper.markParsing(1L)).thenReturn(1);
        when(parser.parse(any())).thenReturn(new ParsedDocument("解析出的正文", "text/plain"));
        when(chunker.chunk(anyString())).thenReturn(new ChunkResult(
                List.of(chunkRecord(0, "解析出的正文", null)), false));
        when(documentMapper.markEmbedding(eq(1L), anyInt(), anyInt(), anyInt(), eq(false))).thenReturn(1);

        service.ingestParse(1L);

        verify(parser).parse(any());
        verify(documentMapper).saveNormalizedText(eq(1L), eq("解析出的正文"), anyInt());
        verify(queue).sendEmbed(1L);
    }

    @Test
    void ingestParseWhenReadyShouldDoNothing() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "READY"));

        service.ingestParse(1L);

        verifyNoInteractions(parser, chunker);
        verify(queue, never()).sendEmbed(anyLong());
    }

    @Test
    void ingestParseWhenEmbeddingShouldOnlyResendEmbed() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));

        service.ingestParse(1L);

        verify(queue).sendEmbed(1L);
        verifyNoInteractions(parser, chunker);
        verify(documentMapper, never()).markParsing(anyLong());
    }

    @Test
    void ingestParseWhenDeletedShouldDoNothing() {
        when(documentMapper.selectById(1L)).thenReturn(null);

        service.ingestParse(1L);

        verifyNoInteractions(parser, chunker);
        verify(queue, never()).sendEmbed(anyLong());
    }

    @Test
    void ingestParseShouldStopWhenStatusCasFails() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "PENDING"));
        when(documentMapper.markParsing(1L)).thenReturn(0);

        service.ingestParse(1L);

        verifyNoInteractions(parser, chunker);
        verify(queue, never()).sendEmbed(anyLong());
    }

    // ---------- embed ----------

    @Test
    void ingestEmbedShouldWriteOnlyPendingChunksAndMarkReady() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));
        when(chunkMapper.selectPendingBatch(1L, 10)).thenReturn(
                List.of(chunk(0, "a", null), chunk(1, "b", null)), List.of());
        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        when(chunkMapper.countByDocumentId(1L)).thenReturn(2);
        when(chunkMapper.sumTokenCount(1L)).thenReturn(30);

        service.ingestEmbed(1L);

        verify(chunkWriter).writeBatch(anyList(), anyList(), eq("text-embedding-v4"));
        verify(documentMapper).touchProgress(1L);
        verify(documentMapper).markReady(1L, 2, 30);
    }

    @Test
    void ingestEmbedShouldIncludeHeadingInEmbeddingInput() {
        DocumentChunk chunk = chunk(0, "正文", "部署 > Docker");
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));
        when(chunkMapper.selectPendingBatch(1L, 10)).thenReturn(List.of(chunk), List.of());
        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));
        when(chunkMapper.countByDocumentId(1L)).thenReturn(1);
        when(chunkMapper.sumTokenCount(1L)).thenReturn(5);

        service.ingestEmbed(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embed(captor.capture());
        assertThat(captor.getValue()).containsExactly("部署 > Docker\n正文");
    }

    @Test
    void ingestEmbedWhenNotEmbeddingShouldDoNothing() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "PENDING"));

        service.ingestEmbed(1L);

        verifyNoInteractions(embeddingClient, chunkWriter);
    }

    @Test
    void ingestEmbedShouldStopWhenDocumentDeletedMidway() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"), null);

        service.ingestEmbed(1L);

        verifyNoInteractions(embeddingClient, chunkWriter);
        verify(documentMapper, never()).markReady(anyLong(), anyInt(), anyInt());
    }

    // ---------- 重建 / 删除 / 查询 ----------

    @Test
    void reindexShouldResetChunksAndSendParse() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "READY"));
        when(documentMapper.resetForReindex(1L)).thenReturn(1);

        service.reindex(1L);

        verify(chunkMapper).deleteByDocumentId(1L);
        verify(queue).sendParse(1L);
    }

    @Test
    void reindexWhileProcessingShouldConflict() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "PARSING"));
        when(documentMapper.resetForReindex(1L)).thenReturn(0);

        BusinessException e = catchThrowableOfType(() -> service.reindex(1L), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_BUSY);
        verify(queue, never()).sendParse(anyLong());
    }

    @Test
    void deleteWhileProcessingShouldConflict() {
        when(documentMapper.selectById(1L)).thenReturn(document(1L, "EMBEDDING"));

        BusinessException e = catchThrowableOfType(() -> service.delete(1L), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_BUSY);
        verify(documentMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteShouldSoftDeleteChunksAndFile() {
        Document document = document(1L, "READY");
        document.setStoragePath("data/documents/x.txt");
        when(documentMapper.selectById(1L)).thenReturn(document);

        service.delete(1L);

        verify(documentMapper).deleteById(1L);
        verify(chunkMapper).deleteByDocumentId(1L);
        verify(storage).delete("data/documents/x.txt");
    }

    @Test
    void listShouldRejectUnknownStatus() {
        BusinessException e = catchThrowableOfType(
                () -> service.list(1, 20, "DONE"), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void chunksShouldReportNotFoundForMissingDocument() {
        when(documentMapper.selectById(9L)).thenReturn(null);

        BusinessException e = catchThrowableOfType(
                () -> service.chunks(9L, 1, 20), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void listShouldClampPageSize() {
        when(documentMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<Document> page = invocation.getArgument(0);
            assertThat(page.getSize()).isEqualTo(100);
            page.setRecords(List.of());
            return page;
        });

        PageResult<?> result = service.list(0, 9999, null);

        assertThat(result.getSize()).isEqualTo(100);
    }

    // ---------- 失败标记 ----------

    @Test
    void markFailedShouldTruncateLongMessage() {
        service.markFailed(1L, "x".repeat(900));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(documentMapper).markFailed(eq(1L), captor.capture());
        assertThat(captor.getValue()).hasSize(500);
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Document document(Long id, String status) {
        Document document = new Document();
        document.setId(id);
        document.setStatus(status);
        document.setTitle("标题");
        document.setFileName("a.txt");
        document.setMimeType("text/plain");
        document.setFileSize(5L);
        document.setStoragePath("data/documents/a.txt");
        document.setChunkCount(0);
        document.setCharCount(0);
        document.setTokenCount(0);
        document.setRetryCount(0);
        document.setChunkTruncated(false);
        return document;
    }

    private static Chunk chunkRecord(int index, String content, String heading) {
        return new Chunk(index, content, heading, 0, content.length(), content.length(), content.length());
    }

    private static DocumentChunk chunk(int index, String content, String heading) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId((long) (index + 1));
        chunk.setDocumentId(1L);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setHeading(heading);
        chunk.setCharStart(0);
        chunk.setCharEnd(content.length());
        chunk.setCharCount(content.length());
        chunk.setTokenCount(content.length());
        return chunk;
    }
}
