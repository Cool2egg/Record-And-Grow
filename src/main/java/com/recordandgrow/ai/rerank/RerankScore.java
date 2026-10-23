package com.recordandgrow.ai.rerank;

/**
 * 一条精排结果：{@code index} 是输入列表里的下标，{@code score} 是模型给的相关度。
 *
 * <p>分数量纲由供应商决定（DashScope 是 0–1），**只用于同一个模型内部的阈值判定**，
 * 换模型必须重新校准闸门阈值。
 */
public record RerankScore(int index, double score) {
}
