package com.recordandgrow.retrieval.document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从排序好的候选里选最终结果：每文档配额 + 相邻块去重 + topK 截断。
 *
 * <p>向量路径与融合路径共用这一份逻辑，避免两处对"哪些块该留"给出不同答案。
 *
 * <p>相邻块去重（同文档且 {@code |ΔchunkIndex| == 1}）是为了省 Prompt 预算：
 * 切块有 60 token 重叠，相邻两块常常在讲同一件事。
 */
public final class DocumentSelection {

    private DocumentSelection() {
    }

    /**
     * @param candidates     已按相关性降序（调用方负责排序与稳定性）
     * @param topK           最多返回多少条，小于 1 按 1 处理
     * @param maxPerDocument 每文档最多几条，小于 1 按 1 处理
     */
    public static List<DocumentHit> select(List<DocumentHit> candidates, int topK, int maxPerDocument) {
        int limit = Math.max(1, topK);
        int perDocumentLimit = Math.max(1, maxPerDocument);

        Map<Long, List<Integer>> selectedIndexes = new HashMap<>();
        Map<Long, Integer> perDocument = new HashMap<>();
        List<DocumentHit> hits = new ArrayList<>(Math.min(limit, candidates.size()));

        for (DocumentHit candidate : candidates) {
            if (hits.size() >= limit) {
                break;
            }
            Long documentId = candidate.documentId();
            int used = perDocument.getOrDefault(documentId, 0);
            if (used >= perDocumentLimit) {
                continue;
            }
            List<Integer> indexes = selectedIndexes.computeIfAbsent(documentId, key -> new ArrayList<>(2));
            boolean adjacent = indexes.stream()
                    .anyMatch(index -> Math.abs(index - candidate.chunkIndex()) == 1);
            if (adjacent) {
                continue;
            }
            indexes.add(candidate.chunkIndex());
            perDocument.put(documentId, used + 1);
            hits.add(candidate);
        }
        return List.copyOf(hits);
    }
}
