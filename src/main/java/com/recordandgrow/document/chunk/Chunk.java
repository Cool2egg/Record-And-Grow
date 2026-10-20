package com.recordandgrow.document.chunk;

/**
 * 一个分块。
 *
 * @param index      块序号，从 0 连续递增（过滤小段后重新编号）
 * @param content    正文；严格等于归一化全文的 {@code [charStart, charEnd)} 子串
 * @param heading    Markdown 标题面包屑，纯文本为 null
 * @param charStart  归一化全文里的起始 code point 下标（含重叠）
 * @param charEnd    结束 code point 下标，半开区间
 * @param charCount  {@code charEnd - charStart}
 * @param tokenCount jtokkit cl100k 计数
 */
public record Chunk(int index,
                    String content,
                    String heading,
                    int charStart,
                    int charEnd,
                    int charCount,
                    int tokenCount) {
}
