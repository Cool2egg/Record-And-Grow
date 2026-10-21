package com.recordandgrow.document;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文档分块 Mapper。
 *
 * <p>向量列的读写都在这里用注解 SQL 完成：写入走 {@code cast(#{embedding} as vector)}，
 * 读取永远不选 embedding——业务层没有任何地方需要 1024 个 float。
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /** 只取待回填的块；id + content 够用，content 是 embedding 的输入。 */
    @Select("""
            SELECT id, document_id, chunk_index, content
              FROM document_chunk
             WHERE document_id = #{documentId} AND embedding IS NULL
             ORDER BY chunk_index
             LIMIT #{limit}
            """)
    List<DocumentChunk> selectPendingBatch(@Param("documentId") Long documentId, @Param("limit") int limit);

    /**
     * 回填一条向量。
     *
     * <p>带 {@code embedding IS NULL} 条件：重复投递或并发消费时，后到的那次影响 0 行，
     * 不会覆盖别处已经写好的向量。
     */
    @Update("""
            UPDATE document_chunk
               SET embedding = cast(#{embedding} AS vector), embedding_model = #{model}
             WHERE id = #{id} AND embedding IS NULL
            """)
    int updateEmbedding(@Param("id") Long id,
                        @Param("embedding") String embedding,
                        @Param("model") String model);

    @Select("SELECT count(*) FROM document_chunk WHERE document_id = #{documentId}")
    int countByDocumentId(@Param("documentId") Long documentId);

    @Select("SELECT coalesce(sum(token_count), 0) FROM document_chunk WHERE document_id = #{documentId}")
    int sumTokenCount(@Param("documentId") Long documentId);

    @Select("SELECT count(*) FROM document_chunk WHERE document_id = #{documentId} AND embedding IS NULL")
    int countPending(@Param("documentId") Long documentId);

    /**
     * 按余弦距离取候选块（模块六检索读路径）。
     *
     * <p>过滤条件全部显式写：{@code @TableLogic} 对自定义 SQL 不生效，所以
     * {@code d.deleted = FALSE} 必须手写；{@code document_chunk} 本身没有 deleted 列。
     * {@code embedding_model} 必须与写入时一致，否则不同模型的向量不可比。
     *
     * <p>阈值不下推到 SQL：{@code WHERE} 里加距离条件会让「索引 + LIMIT 截断」的关系
     * 不可解释。这里只取最近的 {@code limit} 行，阈值/配额/去重都由 Java 侧决定。
     *
     * <p>{@code ORDER BY} 与 HNSW 索引表达式一致（{@code vector_cosine_ops}），
     * 规划器可以选索引；status/deleted/model 是后过滤，过滤后可能不足 {@code limit} 行。
     */
    @Select("""
            SELECT c.id AS chunk_id, c.document_id, d.title, c.chunk_index, c.heading, c.content,
                   c.char_start, c.char_end, c.token_count,
                   c.embedding <=> cast(#{vector} AS vector) AS distance
              FROM document_chunk c
              JOIN document d ON d.id = c.document_id
             WHERE d.deleted = FALSE
               AND d.status = 'READY'
               AND c.embedding IS NOT NULL
               AND c.embedding_model = #{model}
             ORDER BY c.embedding <=> cast(#{vector} AS vector), c.document_id, c.chunk_index, c.id
             LIMIT #{limit}
            """)
    List<DocumentSearchRow> search(@Param("vector") String vector,
                                   @Param("model") String model,
                                   @Param("limit") int limit);


    /** 重跑前清空旧块；物理删除，不上软删。 */
    @Delete("DELETE FROM document_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
