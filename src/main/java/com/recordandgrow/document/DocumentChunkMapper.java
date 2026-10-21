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

    /** 重跑前清空旧块；物理删除，不上软删。 */
    @Delete("DELETE FROM document_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
