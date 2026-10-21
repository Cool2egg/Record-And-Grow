package com.recordandgrow.infra.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码枚举。
 *
 * <p>业务码（{@code code}）与 HTTP 状态码（{@code httpStatus}）分离：
 * 业务码用于前端/调用方按业务规则处理，HTTP 状态码用于传输层语义。
 */
public enum ErrorCode {

    SUCCESS(0, "ok", HttpStatus.OK),
    BAD_REQUEST(40000, "请求参数错误", HttpStatus.BAD_REQUEST),
    NOT_FOUND(40400, "资源不存在", HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(50000, "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    DB_UNAVAILABLE(50300, "数据库不可用", HttpStatus.SERVICE_UNAVAILABLE),
    AI_UNAVAILABLE(50301, "AI 服务暂时不可用", HttpStatus.SERVICE_UNAVAILABLE),
    AI_UPSTREAM_ERROR(50201, "AI 服务返回异常", HttpStatus.BAD_GATEWAY),
    UNSUPPORTED_MEDIA_TYPE(41500, "不支持的文件类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DOCUMENT_PARSE_FAILED(42200, "文档解析失败", HttpStatus.UNPROCESSABLE_ENTITY),
    DOCUMENT_BUSY(40900, "文档正在处理中", HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(41300, "文件过大", HttpStatus.PAYLOAD_TOO_LARGE),
    QUEUE_UNAVAILABLE(50302, "消息队列不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
