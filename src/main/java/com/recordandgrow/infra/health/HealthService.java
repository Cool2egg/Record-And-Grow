package com.recordandgrow.infra.health;

import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 健康检查服务：真实探活数据库，而不是只返回固定成功。
 *
 * <p>探活内容：
 * <ol>
 *   <li>执行 {@code SELECT 1} 验证数据库连通；</li>
 *   <li>查询 {@code pg_extension} 验证 pgvector 扩展已安装。</li>
 * </ol>
 * 任一步失败都会抛出 {@link BusinessException}（{@link ErrorCode#DB_UNAVAILABLE}），
 * 由全局异常处理器转成 HTTP 503。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcTemplate jdbcTemplate;

    public HealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HealthStatus check() {
        probeDatabase();
        boolean vectorInstalled = checkVectorExtension();
        if (!vectorInstalled) {
            throw new BusinessException(ErrorCode.DB_UNAVAILABLE, "pgvector 扩展未安装");
        }
        return HealthStatus.up(true);
    }

    private void probeDatabase() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                throw new BusinessException(ErrorCode.DB_UNAVAILABLE, "数据库探活失败");
            }
        } catch (DataAccessException e) {
            log.warn("数据库不可用: {}", e.getMessage());
            throw new BusinessException(ErrorCode.DB_UNAVAILABLE, e);
        }
    }

    private boolean checkVectorExtension() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            log.warn("查询 pgvector 扩展失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.DB_UNAVAILABLE, e);
        }
    }
}
