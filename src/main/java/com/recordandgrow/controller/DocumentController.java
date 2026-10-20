package com.recordandgrow.controller;

import com.recordandgrow.document.DocumentIngestService;
import com.recordandgrow.document.dto.DocumentChunkResponse;
import com.recordandgrow.document.dto.DocumentRegisterResult;
import com.recordandgrow.document.dto.DocumentResponse;
import com.recordandgrow.document.dto.DocumentUploadResponse;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.ApiResponse;
import com.recordandgrow.infra.response.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档接口。
 *
 * <p>上传只做接收、校验、落盘、建记录；真正解析在队列里。
 * 发消息放在 controller、{@code register()} 返回之后：service 里没有大事务，
 * 但「先落库再发」的顺序必须由调用方保证，避免消费端查不到刚插入的行。
 *
 * <p>返回码区分「新建」与「命中已有内容」：202 / 200。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestService service;
    private final DocumentIngestQueue queue;

    public DocumentController(DocumentIngestService service, DocumentIngestQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> upload(
            @RequestParam("file") MultipartFile file) {
        DocumentRegisterResult result = service.register(
                file.getOriginalFilename(), file.getSize(), () -> open(file));

        if (result.sendParse()) {
            try {
                queue.sendParse(result.document().getId());
            } catch (RuntimeException e) {
                // 记录已经落库，交给对账补发；这里如实告诉调用方「没排上队」
                throw new BusinessException(ErrorCode.QUEUE_UNAVAILABLE,
                        "消息队列不可用，文档已保存，稍后会自动重试");
            }
        }

        HttpStatus status = result.deduplicated() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(DocumentUploadResponse.from(result)));
    }

    @GetMapping
    public ApiResponse<PageResult<DocumentResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(page, size, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @GetMapping("/{id}/chunks")
    public ApiResponse<PageResult<DocumentChunkResponse>> chunks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.chunks(id, page, size));
    }

    @PostMapping("/{id}/reindex")
    public ResponseEntity<ApiResponse<Void>> reindex(@PathVariable Long id) {
        service.reindex(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success();
    }

    private static InputStream open(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
    }
}
