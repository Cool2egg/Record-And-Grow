package com.recordandgrow.retrieval.document;

import java.util.List;
import java.util.Objects;

/**
 * 证据闸门：整批最高精排分低于阈值时，**整批丢弃**，而不是逐条过滤。
 *
 * <p>逐条卡会制造"留了三条不相关的"这种更糟的结果——模型拿到三条弱证据，仍然会硬答。
 * 整批丢的好处是语义干净：要么这批证据足够好，要么明确告诉用户"没找到"。
 *
 * <p>没有精排分（精排关闭或降级）时放行：闸门依赖精排，没有精排就没有判断依据，
 * 此时放行与模块六的行为一致。
 */
public final class EvidenceGate {

    private EvidenceGate() {
    }

    public static boolean keep(List<Double> rerankScores, double minScore) {
        if (rerankScores == null || rerankScores.isEmpty()) {
            return true;
        }
        double max = rerankScores.stream()
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
        return max >= minScore;
    }
}
