package com.recordandgrow.document.parse;

/**
 * 一次解析的产物。
 *
 * @param text     原始抽取文本（未归一化；归一化由 {@code TextNormalizer} 统一做）
 * @param mimeType Tika 嗅探出的 MIME，不是客户端声明的 Content-Type
 */
public record ParsedDocument(String text, String mimeType) {
}
