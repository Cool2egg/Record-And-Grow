package com.recordandgrow.controller;

import com.recordandgrow.document.Document;
import com.recordandgrow.document.DocumentChunk;
import com.recordandgrow.document.DocumentIngestService;
import com.recordandgrow.document.dto.DocumentChunkResponse;
import com.recordandgrow.document.dto.DocumentRegisterResult;
import com.recordandgrow.document.dto.DocumentResponse;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.exception.GlobalExceptionHandler;
import com.recordandgrow.infra.response.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档接口：状态码、响应字段裁剪、发消息时机。
 *
 * <p>standalone MockMvc，不启动 Spring 上下文，也不连库。
 */
class DocumentControllerTest {

    private DocumentIngestService service;
    private DocumentIngestQueue queue;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DocumentIngestService.class);
        queue = mock(DocumentIngestQueue.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DocumentController(service, queue))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void uploadNewDocumentShouldReturn202AndSendParse() throws Exception {
        when(service.register(anyString(), anyLong(), any()))
                .thenReturn(new DocumentRegisterResult(response(1L, "PENDING"), false, true));

        mockMvc.perform(multipart("/api/documents")
                        .file(file("a.txt", "hello")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deduplicated").value(false))
                .andExpect(jsonPath("$.data.document.id").value(1))
                .andExpect(jsonPath("$.data.document.status").value("PENDING"));

        verify(service).register(eq("a.txt"), eq(5L), any());
        verify(queue).sendParse(1L);
    }

    @Test
    void duplicateUploadShouldReturn200AndNotResendWhenReady() throws Exception {
        when(service.register(anyString(), anyLong(), any()))
                .thenReturn(new DocumentRegisterResult(response(2L, "READY"), true, false));

        mockMvc.perform(multipart("/api/documents")
                        .file(file("a.txt", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deduplicated").value(true))
                .andExpect(jsonPath("$.data.document.status").value("READY"));

        verify(queue, never()).sendParse(anyLong());
    }

    @Test
    void duplicateUploadOfPendingDocumentShouldResendParse() throws Exception {
        when(service.register(anyString(), anyLong(), any()))
                .thenReturn(new DocumentRegisterResult(response(3L, "PENDING"), true, true));

        mockMvc.perform(multipart("/api/documents")
                        .file(file("a.txt", "hello")))
                .andExpect(status().isOk());

        verify(queue).sendParse(3L);
    }

    @Test
    void queueFailureShouldReturn503() throws Exception {
        when(service.register(anyString(), anyLong(), any()))
                .thenReturn(new DocumentRegisterResult(response(4L, "PENDING"), false, true));
        doThrow(new IllegalStateException("broker down")).when(queue).sendParse(4L);

        mockMvc.perform(multipart("/api/documents")
                        .file(file("a.txt", "hello")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.QUEUE_UNAVAILABLE.getCode()));
    }

    @Test
    void missingFilePartShouldReturn400() throws Exception {
        mockMvc.perform(multipart("/api/documents"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    void getShouldNotExposeStoragePathOrNormalizedText() throws Exception {
        when(service.get(1L)).thenReturn(response(1L, "READY"));

        mockMvc.perform(get("/api/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.storagePath").doesNotExist())
                .andExpect(jsonPath("$.data.normalizedText").doesNotExist());
    }

    @Test
    void getMissingDocumentShouldReturn404() throws Exception {
        when(service.get(9L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));

        mockMvc.perform(get("/api/documents/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    void chunksShouldNotExposeEmbedding() throws Exception {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(11L);
        chunk.setChunkIndex(0);
        chunk.setContent("正文");
        chunk.setCharStart(0);
        chunk.setCharEnd(2);
        chunk.setCharCount(2);
        chunk.setTokenCount(3);
        when(service.chunks(1L, 1, 20)).thenReturn(
                PageResult.of(List.of(DocumentChunkResponse.from(chunk)), 1, 1, 20));

        mockMvc.perform(get("/api/documents/1/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").value("正文"))
                .andExpect(jsonPath("$.data.items[0].embedding").doesNotExist());
    }

    @Test
    void listShouldReturnPageShape() throws Exception {
        when(service.list(1, 20, "READY"))
                .thenReturn(PageResult.of(List.of(response(1L, "READY")), 1, 1, 20));

        mockMvc.perform(get("/api/documents").param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].id").value(1));
    }

    @Test
    void listWithInvalidStatusShouldReturn400() throws Exception {
        when(service.list(anyInt(), anyInt(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "status 不合法"));

        mockMvc.perform(get("/api/documents").param("status", "DONE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reindexShouldReturn202() throws Exception {
        mockMvc.perform(post("/api/documents/1/reindex"))
                .andExpect(status().isAccepted());

        verify(service).reindex(1L);
    }

    @Test
    void reindexWhileBusyShouldReturn409() throws Exception {
        doThrow(new BusinessException(ErrorCode.DOCUMENT_BUSY, "处理中"))
                .when(service).reindex(1L);

        mockMvc.perform(post("/api/documents/1/reindex"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOCUMENT_BUSY.getCode()));
    }

    @Test
    void deleteShouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/documents/1"))
                .andExpect(status().isOk());

        verify(service).delete(1L);
    }

    @Test
    void deleteWhileBusyShouldReturn409() throws Exception {
        doThrow(new BusinessException(ErrorCode.DOCUMENT_BUSY, "处理中"))
                .when(service).delete(1L);

        mockMvc.perform(delete("/api/documents/1"))
                .andExpect(status().isConflict());
    }

    @Test
    void oversizedFileShouldReturn413() throws Exception {
        when(service.register(anyString(), anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "文件过大"));

        mockMvc.perform(multipart("/api/documents").file(file("a.txt", "hello")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYLOAD_TOO_LARGE.getCode()));
    }

    @Test
    void unsupportedTypeShouldReturn415() throws Exception {
        when(service.register(anyString(), anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "不支持"));

        mockMvc.perform(multipart("/api/documents").file(file("a.docx", "hello")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode()));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static DocumentResponse response(Long id, String status) {
        Document document = new Document();
        document.setId(id);
        document.setTitle("标题");
        document.setFileName("a.txt");
        document.setMimeType("text/plain");
        document.setFileSize(5L);
        document.setStoragePath("data/documents/a.txt");
        document.setNormalizedText("全文");
        document.setStatus(status);
        document.setChunkCount(1);
        document.setCharCount(10);
        document.setTokenCount(5);
        document.setChunkTruncated(false);
        return DocumentResponse.from(document);
    }
}
