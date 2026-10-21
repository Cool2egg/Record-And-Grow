package com.recordandgrow.document.dto;

/**
 * 上传结果。
 *
 * @param document     文档视图
 * @param deduplicated 是否命中已有内容（sha256 相同）
 * @param sendParse    调用方是否需要在事务提交后补发一次 parse 消息
 *                     （新建，或命中的文档仍停在 PENDING）
 */
public record DocumentRegisterResult(DocumentResponse document, boolean deduplicated, boolean sendParse) {
}
