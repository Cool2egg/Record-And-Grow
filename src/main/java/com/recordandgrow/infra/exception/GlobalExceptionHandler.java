package com.recordandgrow.infra.exception;

import com.recordandgrow.infra.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：把所有异常统一翻译成 {@link ApiResponse}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "参数校验失败: " + detail));
    }

    /**
     * 数据库资源获取失败（如连接池拿不到连接、数据库宕机）。
     *
     * <p>这类失败是「依赖暂时不可用」，不是程序 bug：返回 503 而非 500，
     * 与 {@code /api/health} 的语义保持一致，也便于调用方按状态码决定是否重试。
     * 日志只记 WARN + 消息，不打堆栈——依赖故障是预期内的情况，不应淹没真正的程序错误。
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessResourceFailure(DataAccessResourceFailureException e) {
        log.warn("数据库资源不可用: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.DB_UNAVAILABLE));
    }

    /**
     * 数据库瞬时故障（如查询超时、并发冲突）。
     *
     * <p>瞬时故障重试有意义，同样映射为 503。
     */
    @ExceptionHandler(TransientDataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleTransientDataAccessException(TransientDataAccessException e) {
        log.warn("数据库瞬时故障: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.DB_UNAVAILABLE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
