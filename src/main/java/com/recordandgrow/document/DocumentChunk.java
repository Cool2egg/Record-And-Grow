package com.recordandgrow.document;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 文档分块，对应表 document_chunk。
 *
 * <p>故意不映射 {@code embedding} 列：向量只通过 Mapper 里的自定义 SQL 写入，
 * 读路径永远不碰它，省掉「PostgreSQL vector → Java 类型」的转换，也保证任何
 * {@code selectById} 都不会把 1024 个 float 拖进内存。
 *
 * <p>{@code charStart / charEnd} 是归一化全文里的 code point 下标（半开区间），
 * 满足 {@code normalizedText.substring(cp(charStart), cp(charEnd)) == content}；
 * 相邻块允许重叠，重叠部分算进下一块的区间。
 */
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    /** Markdown 标题面包屑，如「部署 > Docker」；纯文本为 null。 */
    private String heading;

    private Integer charStart;

    private Integer charEnd;

    private Integer charCount;

    private Integer tokenCount;

    private String embeddingModel;

    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public Integer getCharStart() {
        return charStart;
    }

    public void setCharStart(Integer charStart) {
        this.charStart = charStart;
    }

    public Integer getCharEnd() {
        return charEnd;
    }

    public void setCharEnd(Integer charEnd) {
        this.charEnd = charEnd;
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

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
