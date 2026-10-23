package com.recordandgrow.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会话表 Mapper。
 *
 * <p>计数与摘要水位都用「带条件的 UPDATE」原子更新，避免先查后改的竞态。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Update("""
            UPDATE conversation
               SET message_count = message_count + 1, updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
            """)
    int bumpMessageCount(@Param("id") Long id);

    @Update("""
            UPDATE conversation
               SET title = #{title}, updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
            """)
    int updateTitle(@Param("id") Long id, @Param("title") String title);

    @Update("""
            UPDATE conversation
               SET summary = #{summary}, summary_upto_message_id = #{uptoId}, updated_at = now()
             WHERE id = #{id} AND deleted = FALSE
               AND summary_upto_message_id < #{uptoId}
            """)
    int updateSummary(@Param("id") Long id,
                      @Param("summary") String summary,
                      @Param("uptoId") long uptoId);
}
