package com.recordandgrow.retrieval.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合（Reciprocal Rank Fusion）。
 *
 * <p>{@code score(chunk) = Σ 1 / (k + rank + 1)}，**只看名次，不看分数**：
 * 向量是余弦、关键词是 BM25，量纲不可比，任何加权求和都是在编数字。
 *
 * <p>k 的作用是压低头部差距：k 越小，第 1 名越占绝对优势；k 越大，名次越平。
 * 候选池只有 20–40 条时用 20（经典 60 是为大池子准备的）。
 *
 * <p>只出现在一路里的 chunk 也会得到分数（另一路没有贡献），这正是融合想要的效果：
 * 「两路都靠前」比「一路第一」更可信。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * @param channels 每个通道已按相关性降序的结果；空列表会被忽略
     * @param k        RRF 常数，小于 1 时按 1 处理
     * @return 按融合分降序的命中；同分时保持首次出现顺序（稳定）
     */
    public static List<DocumentHit> fuse(List<List<DocumentHit>> channels, int k) {
        int safeK = Math.max(1, k);
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, DocumentHit> firstSeen = new LinkedHashMap<>();

        for (List<DocumentHit> channel : channels) {
            if (channel == null || channel.isEmpty()) {
                continue;
            }
            int rank = 0;
            for (DocumentHit hit : channel) {
                if (hit == null || hit.chunkId() == null) {
                    rank++;
                    continue;
                }
                scores.merge(hit.chunkId(), 1.0 / (safeK + rank + 1), Double::sum);
                firstSeen.putIfAbsent(hit.chunkId(), hit);
                rank++;
            }
        }

        List<DocumentHit> fused = new ArrayList<>(firstSeen.size());
        for (Map.Entry<Long, DocumentHit> entry : firstSeen.entrySet()) {
            DocumentHit hit = entry.getValue();
            fused.add(new DocumentHit(
                    hit.chunkId(),
                    hit.documentId(),
                    hit.title(),
                    hit.heading(),
                    hit.content(),
                    scores.getOrDefault(entry.getKey(), 0.0),
                    hit.charStart(),
                    hit.charEnd()));
        }
        // 稳定排序：同分时保留插入顺序
        fused.sort(Comparator.comparingDouble(DocumentHit::score).reversed());
        return fused;
    }
}
