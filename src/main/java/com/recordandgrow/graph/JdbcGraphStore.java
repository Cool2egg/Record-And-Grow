package com.recordandgrow.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Postgres 图谱存储。
 *
 * <p>规模假设：单用户、几十篇文档、几千条边。查询是「实体名 LIKE + 1 跳」，
 * 不做多跳展开——多跳很容易把无关实体串进来，收益不抵噪声。
 */
@Repository
public class JdbcGraphStore implements GraphStore {

    private final JdbcTemplate jdbc;

    public JdbcGraphStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void replaceDocument(Long documentId, List<GraphTriple> triples) {
        jdbc.update("DELETE FROM graph_edge WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM graph_node WHERE document_id = ?", documentId);
        if (triples == null || triples.isEmpty()) {
            return;
        }

        Map<String, Long> ids = new HashMap<>();
        for (GraphTriple triple : triples) {
            if (!triple.isValid()) {
                continue;
            }
            Long sourceId = ids.computeIfAbsent(normalize(triple.source()),
                    key -> insertNode(documentId, triple.source(), key));
            Long targetId = ids.computeIfAbsent(normalize(triple.target()),
                    key -> insertNode(documentId, triple.target(), key));
            jdbc.update("""
                    INSERT INTO graph_edge (source_id, target_id, relation, document_id, weight)
                    VALUES (?, ?, ?, ?, 1.0)
                    ON CONFLICT (source_id, target_id, relation, document_id) DO NOTHING
                    """, sourceId, targetId, triple.relation(), documentId);
        }
    }

    private Long insertNode(Long documentId, String name, String normalized) {
        return jdbc.queryForObject("""
                INSERT INTO graph_node (name, normalized, type, document_id)
                VALUES (?, ?, 'CONCEPT', ?)
                ON CONFLICT (document_id, normalized) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class, name, normalized, documentId);
    }

    @Override
    public List<GraphHit> search(List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        StringBuilder where = new StringBuilder("(1 = 0");
        List<Object> args = new ArrayList<>();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            where.append(" OR s.normalized LIKE ? ESCAPE '\\' OR t.normalized LIKE ? ESCAPE '\\'");
            String pattern = "%" + escapeLike(term) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        where.append(")");
        if (args.isEmpty()) {
            return List.of();
        }
        args.add(Math.max(1, limit));

        return jdbc.query("""
                SELECT e.id, s.name AS source, e.relation, t.name AS target,
                       e.document_id, d.title
                  FROM graph_edge e
                  JOIN graph_node s ON s.id = e.source_id
                  JOIN graph_node t ON t.id = e.target_id
                  JOIN document d ON d.id = e.document_id
                 WHERE """ + where + """
                   AND d.deleted = FALSE
                 ORDER BY e.weight DESC, e.id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new GraphHit(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("relation"),
                rs.getString("target"),
                rs.getLong("document_id"),
                rs.getString("title")), args.toArray());
    }

    @Override
    public void deleteDocument(Long documentId) {
        jdbc.update("DELETE FROM graph_edge WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM graph_node WHERE document_id = ?", documentId);
    }

    @Override
    public long countNodes() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM graph_node", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countEdges() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM graph_edge", Long.class);
        return count == null ? 0 : count;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
