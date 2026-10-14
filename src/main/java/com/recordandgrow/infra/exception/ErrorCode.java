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
    DB_UNAVAILABLE(50300, "数据库不可用", HttpStatus.SERVICE_UNAVAILABLE);

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
