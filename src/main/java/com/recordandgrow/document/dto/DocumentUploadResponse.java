package com.recordandgrow.document.dto;

/**
 * 上传响应。{@code sendParse} 是内部调度细节，不对外暴露。
 */
public record DocumentUploadResponse(DocumentResponse document, boolean deduplicated) {

    public static DocumentUploadResponse from(DocumentRegisterResult result) {
        return new DocumentUploadResponse(result.document(), result.deduplicated());
    }
}
