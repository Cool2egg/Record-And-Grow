package com.recordandgrow.document;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文档表 Mapper。
 *
 * <p>状态迁移全部写成「带前置条件的 UPDATE + 返回影响行数」：影响 0 行说明状态已被
 * 别的执行者改掉（重复投递、并发消费、删除），调用方据此安静退出，而不是先查后改。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    @Select("SELECT * FROM document WHERE content_sha256 = #{sha256} AND deleted = FALSE LIMIT 1")
    Document selectActiveBySha256(@Param("sha256") String sha256);

    /**
     * PENDING / PARSING / FAILED → PARSING。
     *
     * <p>允许 PARSING 自身重复进入，是为了让「上次处理到一半进程被杀」的文档能重跑；
     * READY 与 EMBEDDING 不在条件里：前者不该再解析，后者应走 embed 阶段。
     */
    @Update("""
            UPDATE document
               SET status = 'PARSING', error_message = NULL,
                   last_progress_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
               AND status IN ('PENDING', 'PARSING', 'FAILED')
            """)
    int markParsing(@Param("id") Long id);

    /** 分块落库之后、发 embed 消息之前调用。 */
    @Update("""
            UPDATE document
               SET status = 'EMBEDDING', chunk_count = #{chunkCount}, char_count = #{charCount},
                   token_count = #{tokenCount}, chunk_truncated = #{truncated},
                   error_message = NULL, last_progress_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
               AND status IN ('PARSING', 'EMBEDDING')
            """)
    int markEmbedding(@Param("id") Long id,
                      @Param("chunkCount") int chunkCount,
                      @Param("charCount") int charCount,
                      @Param("tokenCount") int tokenCount,
                      @Param("truncated") boolean truncated);

    /** 全部块回填完成。计数以 chunk 表为准，不用分块器的估算值。 */
    @Update("""
            UPDATE document
               SET status = 'READY', chunk_count = #{chunkCount}, token_count = #{tokenCount},
                   error_message = NULL, last_progress_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE AND status = 'EMBEDDING'
            """)
    int markReady(@Param("id") Long id,
                  @Param("chunkCount") int chunkCount,
                  @Param("tokenCount") int tokenCount);

    @Update("""
            UPDATE document
               SET status = 'FAILED', error_message = #{errorMessage},
                   last_progress_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
            """)
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /** 对账补发一次，重试次数 +1；超过上限由对账任务改判 FAILED。 */
    @Update("""
            UPDATE document
               SET retry_count = retry_count + 1, last_progress_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
            """)
    int bumpRetryCount(@Param("id") Long id);

    /**
     * 解析结果落库：归一化全文 + 字符数。
     *
     * <p>normalized_text 是 reindex 的唯一来源，char_start/char_end 也只有在同一份
     * 归一化文本上才有意义，所以必须在切块之前写进去。
     */
    @Update("""
            UPDATE document
               SET normalized_text = #{text}, char_count = #{charCount},
                   last_progress_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
            """)
    int saveNormalizedText(@Param("id") Long id,
                           @Param("text") String text,
                           @Param("charCount") int charCount);

    /** 心跳：每批 embedding 写完刷新一次，对账据此区分「慢」与「卡住」。 */
    @Update("UPDATE document SET last_progress_at = now() WHERE id = #{id} AND deleted = FALSE")
    int touchProgress(@Param("id") Long id);

    /**
     * 是否存在可检索的文档（模块六的第一步）。
     *
     * <p>没有 READY 文档时整条文档检索路径都不执行：不调查询 embedding、不查 SQL，
     * 既省一次网络调用，也避免「没文档的用户因为 embedding 抖动被降级」。
     * 可复用 {@code idx_document_status_progress} 的前缀。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM document WHERE deleted = FALSE AND status = 'READY')")
    boolean existsReady();


    /** reindex 前的复位：清空处理结果，回到 PENDING。 */
    @Update("""
            UPDATE document
               SET status = 'PENDING', chunk_count = 0, char_count = 0, token_count = 0,
                   retry_count = 0, chunk_truncated = FALSE, error_message = NULL,
                   last_progress_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
               AND status IN ('READY', 'FAILED')
            """)
    int resetForReindex(@Param("id") Long id);

    @Select("""
            SELECT * FROM document
             WHERE deleted = FALSE
               AND status IN ('PENDING', 'PARSING', 'EMBEDDING')
               AND last_progress_at < now() - make_interval(secs => cast(#{staleSeconds} AS double precision))
             ORDER BY last_progress_at
             LIMIT 100
            """)
    List<Document> selectStale(@Param("staleSeconds") long staleSeconds);
}
