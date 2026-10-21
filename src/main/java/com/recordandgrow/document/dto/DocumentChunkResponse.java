package com.recordandgrow.document.dto;

import com.recordandgrow.document.DocumentChunk;

/**
 * 分块的对外视图：正文、标题、偏移与 token 数，用于核对切块质量。
 *
 * <p>不含 embedding——1024 个 float 进 JSON 既没意义也太大。
 */
public class DocumentChunkResponse {

    private Long id;
    private Integer chunkIndex;
    private String content;
    private String heading;
    private Integer charStart;
    private Integer charEnd;
    private Integer charCount;
    private Integer tokenCount;

    public static DocumentChunkResponse from(DocumentChunk chunk) {
        DocumentChunkResponse response = new DocumentChunkResponse();
        response.id = chunk.getId();
        response.chunkIndex = chunk.getChunkIndex();
        response.content = chunk.getContent();
        response.heading = chunk.getHeading();
        response.charStart = chunk.getCharStart();
        response.charEnd = chunk.getCharEnd();
        response.charCount = chunk.getCharCount();
        response.tokenCount = chunk.getTokenCount();
        return response;
    }

    public Long getId() {
        return id;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public String getHeading() {
        return heading;
    }

    public Integer getCharStart() {
        return charStart;
    }

    public Integer getCharEnd() {
        return charEnd;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }
}
