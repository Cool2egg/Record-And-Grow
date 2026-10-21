package com.recordandgrow.document;

/**
 * {@link DocumentChunkMapper#search} 的投影：一条候选文档块。
 *
 * <p>用类 + getter/setter 而不是 record：不赌 MyBatis 对 record 构造器的映射规则，
 * 与仓库里其它 Mapper 的返回类型保持一致。故意不含 {@code embedding}——
 * 1024 个 float 永远不拉回 Java。
 */
public class DocumentSearchRow {

    private Long chunkId;

    private Long documentId;

    private String title;

    private Integer chunkIndex;

    private String heading;

    private String content;

    private Integer charStart;

    private Integer charEnd;

    private Integer tokenCount;

    /** 余弦距离，越小越相似；相似度由调用方按 {@code 1 - distance} 换算。 */
    private Double distance;

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Double getDistance() {
        return distance;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }
}
