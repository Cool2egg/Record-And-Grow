package com.recordandgrow.document.dto;

import com.recordandgrow.document.Document;

import java.time.OffsetDateTime;

/**
 * 文档元信息的对外视图。
 *
 * <p>刻意不包含 {@code storagePath}（宿主机路径）与 {@code normalizedText}（全文）：
 * 前者泄露部署细节，后者体积大且只该服务端用。
 */
public class DocumentResponse {

    private Long id;
    private String title;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private String status;
    private Integer chunkCount;
    private Integer charCount;
    private Integer tokenCount;
    private Boolean chunkTruncated;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static DocumentResponse from(Document document) {
        DocumentResponse response = new DocumentResponse();
        response.id = document.getId();
        response.title = document.getTitle();
        response.fileName = document.getFileName();
        response.mimeType = document.getMimeType();
        response.fileSize = document.getFileSize();
        response.status = document.getStatus();
        response.chunkCount = document.getChunkCount();
        response.charCount = document.getCharCount();
        response.tokenCount = document.getTokenCount();
        response.chunkTruncated = document.getChunkTruncated();
        response.errorMessage = document.getErrorMessage();
        response.createdAt = document.getCreatedAt();
        response.updatedAt = document.getUpdatedAt();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getStatus() {
        return status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public Boolean getChunkTruncated() {
        return chunkTruncated;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
