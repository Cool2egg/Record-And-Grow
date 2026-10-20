package com.recordandgrow.document;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 字符串绑定的最小探针。
 *
 * <p>这是整个模块唯一的 {@code [推断]} 点：JDBC 的 String 参数是 varchar，
 * 靠 {@code cast(? AS vector)} 能不能写进 {@code vector(1024)}。
 * 单独成类、不依赖 V2 迁移，是为了让它成为本机执行清单里的第一条——
 * 它不通，后面的集成测试都不用跑。
 *
 * <p>走的是和 MyBatis 完全相同的路径：{@code PreparedStatement.setString}。
 * 需要 Docker；没有 Docker 的环境（沙箱）自动跳过，不算通过。
 */
@Testcontainers(disabledWithoutDocker = true)
class VectorCastProbeTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recordandgrow")
            .withUsername("recordandgrow")
            .withPassword("recordandgrow");

    @Test
    void stringParameterShouldCastToVectorWithCorrectDimensions() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TEMP TABLE probe (v vector(1024))");

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO probe VALUES (cast(? AS vector))")) {
                insert.setString(1, vectorLiteral(1024));
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }

            try (ResultSet rs = statement.executeQuery("SELECT vector_dims(v) FROM probe")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1024);
            }
        }
    }

    /** 维度不符时数据库应拒绝，证明约束真实生效。 */
    @Test
    void wrongDimensionsShouldBeRejectedByColumnType() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TEMP TABLE probe (v vector(1024))");

            boolean rejected = false;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO probe VALUES (cast(? AS vector))")) {
                insert.setString(1, vectorLiteral(512));
                insert.executeUpdate();
            } catch (java.sql.SQLException e) {
                rejected = true;
                assertThat(e.getMessage()).containsIgnoringCase("dimension");
            }

            assertThat(rejected).as("vector(1024) 列必须拒绝 512 维向量").isTrue();
        }
    }

    private static String vectorLiteral(int dimensions) {
        StringBuilder builder = new StringBuilder(dimensions * 8);
        builder.append('[');
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.4f", 0.1f));
        }
        return builder.append(']').toString();
    }
}
