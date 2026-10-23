package com.recordandgrow.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 消息表 Mapper。
 *
 * <p>历史加载有两条路：
 * <ul>
 *   <li>{@link #selectRecent}：最近 N 条，用于每轮带上下文；</li>
 *   <li>{@link #selectAfter}：摘要水位之后的旧消息，用于滚动摘要。</li>
 * </ul>
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("""
            SELECT * FROM message
             WHERE conversation_id = #{conversationId}
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<Message> selectRecent(@Param("conversationId") Long conversationId,
                               @Param("limit") int limit);

    @Select("""
            SELECT * FROM message
             WHERE conversation_id = #{conversationId} AND id > #{afterId}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<Message> selectAfter(@Param("conversationId") Long conversationId,
                              @Param("afterId") long afterId,
                              @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(id), 0) FROM message WHERE conversation_id = #{conversationId}")
    long maxId(@Param("conversationId") Long conversationId);

    @Select("SELECT COUNT(*) FROM message WHERE conversation_id = #{conversationId}")
    long countByConversation(@Param("conversationId") Long conversationId);
}
