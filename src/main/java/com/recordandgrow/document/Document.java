package com.recordandgrow.document;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 文档元信息，对应表 document。
 *
 * <p>字段分三类：上传时的元信息（title / fileName / mimeType / fileSize / storagePath / sha256）、
 * 处理结果（normalizedText / status / 各类计数 / errorMessage）、系统维护的时间戳。
 *
 * <p>{@code normalizedText} 只供 reindex 与服务端排查，任何响应 DTO 都不许带出去；
 * {@code lastProgressAt} 是处理心跳，与 {@code updatedAt}（记录修改时间）语义不同：
 * 消费过程中 updatedAt 不变，对账只能看 lastProgressAt。
 */
@TableName("document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String fileName;

    private String mimeType;

    private Long fileSize;

    private String storagePath;

    private String contentSha256;

    private String normalizedText;

    /** 取值见 {@link DocumentStatus}。 */
    private String status;

    private Integer chunkCount;

    private Integer charCount;

    private Integer tokenCount;

    /** 对账补发次数，封顶后直接置 FAILED。 */
    private Integer retryCount;

    /** 超过 max-chunks 被截断时为 true，提示检索结果可能不完整。 */
    private Boolean chunkTruncated;

    private String errorMessage;

    /** 最近一次处理进度心跳，对账任务据此判断是否卡住。 */
    private OffsetDateTime lastProgressAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public void setNormalizedText(String normalizedText) {
        this.normalizedText = normalizedText;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Boolean getChunkTruncated() {
        return chunkTruncated;
    }

    public void setChunkTruncated(Boolean chunkTruncated) {
        this.chunkTruncated = chunkTruncated;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public OffsetDateTime getLastProgressAt() {
        return lastProgressAt;
    }

    public void setLastProgressAt(OffsetDateTime lastProgressAt) {
        this.lastProgressAt = lastProgressAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
