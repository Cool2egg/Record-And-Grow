package com.recordandgrow.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 记忆表 Mapper。
 *
 * <p>检索只取可信记忆（FACT / INFERRED），PENDING 永远不参与回答。
 * 用 {@code normalized LIKE} 做子串匹配：单用户记忆量很小，上向量检索的收益
 * 抵不过一次 embedding 调用的延迟与故障面。
 */
@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {

    @Select("""
            SELECT * FROM memory
             WHERE deleted = FALSE AND type IN ('FACT', 'INFERRED')
               AND normalized LIKE CONCAT('%', #{query}, '%')
             ORDER BY updated_at DESC
             LIMIT #{limit}
            """)
    List<Memory> searchTrusted(@Param("query") String query, @Param("limit") int limit);

    @Select("""
            SELECT * FROM memory
             WHERE deleted = FALSE AND type = 'PENDING'
             ORDER BY created_at DESC
             LIMIT #{limit}
            """)
    List<Memory> selectPending(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM memory WHERE deleted = FALSE AND type = 'PENDING'")
    long countPending();

    /** 已存在同内容（任意状态）时返回它，用于去重。 */
    @Select("""
            SELECT * FROM memory
             WHERE deleted = FALSE AND normalized = #{normalized}
             LIMIT 1
            """)
    Memory selectByNormalized(@Param("normalized") String normalized);

    /** 最近记忆正文，用于提炼时告诉模型「已有记忆，不要重复」。 */
    @Select("""
            SELECT content FROM memory
             WHERE deleted = FALSE
             ORDER BY updated_at DESC
             LIMIT #{limit}
            """)
    List<String> selectRecentContents(@Param("limit") int limit);

    /**
     * 确认候选：带状态条件，只有仍是 PENDING 才更新。
     * proposedType 必须显式置 NULL；MyBatis-Plus 默认忽略 null 字段，updateById 清不掉。
     */
    @Update("""
            UPDATE memory
               SET type = #{type}, proposed_type = NULL,
                   confirmed_at = now(), updated_at = now()
             WHERE id = #{id} AND deleted = FALSE AND type = 'PENDING'
            """)
    int confirm(@Param("id") Long id, @Param("type") String type);
}
