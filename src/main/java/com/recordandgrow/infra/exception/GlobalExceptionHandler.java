package com.recordandgrow.infra.exception;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.infra.response.ApiResponse;
import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
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

    /**
     * MyBatis 在连接不可用时会抛出 {@link MyBatisSystemException} / {@link PersistenceException}，
     * 它们不是 Spring 的 {@link DataAccessResourceFailureException}，不会被上面的处理器捕获。
     *
     * <p>只有根因是连接类故障时才映射 503；SQL 语法、约束冲突等程序/数据错误仍然是 500，
     * 避免把所有 MyBatis 异常都误判成「数据库暂时不可用」。
     */
    @ExceptionHandler({MyBatisSystemException.class, PersistenceException.class})
    public ResponseEntity<ApiResponse<Void>> handleMyBatisInfrastructure(Exception e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        if (isConnectionFailure(root) || isConnectionFailure(e)) {
            log.warn("数据库连接不可用: {}", root.getMessage());
            return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.getHttpStatus())
                    .body(ApiResponse.error(ErrorCode.DB_UNAVAILABLE));
        }
        log.error("MyBatis 异常", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    private static boolean isConnectionFailure(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof SQLException sql) {
            String state = sql.getSQLState();
            if (sql instanceof SQLTransientConnectionException
                    || sql instanceof SQLNonTransientConnectionException
                    || (state != null && (state.startsWith("08") || state.startsWith("57P0")))) {
                return true;
            }
        }
        if (t instanceof CannotGetJdbcConnectionException || t instanceof ConnectException) {
            return true;
        }
        String message = t.getMessage();
        return message != null && (message.contains("Connection refused")
                || message.contains("Could not get JDBC Connection")
                || message.contains("Failed to obtain JDBC Connection")
                || message.contains("Connection is not available")
                || message.contains("HikariPool"));
    }

    /**
     * AI 调用失败。
     *
     * <p>业务码与 HTTP 状态码在这里统一：请求/响应格式问题映射 502，
     * 网络、限流、超时、鉴权、余额、未配置统一映射 503。
     * 上游细节只进日志，响应体只给通用文案。
     */
    @ExceptionHandler(AiException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiException(AiException e) {
        ErrorCode code = switch (e.getType()) {
            case INVALID_REQUEST, BAD_RESPONSE, STREAM_INTERRUPTED -> ErrorCode.AI_UPSTREAM_ERROR;
            default -> ErrorCode.AI_UNAVAILABLE;
        };
        log.warn("AI 调用失败: type={}, provider={}, status={}, message={}",
                e.getType(), e.getProvider(), e.getStatusCode(), e.getMessage());
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code));
    }

    /**
     * 客户端已断开（SSE 流式输出时按 Ctrl+C、关页面、网络中断）。
     *
     * <p>响应已经提交且不可写，任何回写都注定失败，只会再产生一条
     * 「Failure in @ExceptionHandler」噪音。这里只记 DEBUG，不返回响应体。
     * 上游的取消由 {@code ChatSseService} 的 onError/cancel 回调负责，与本处理器无关。
     */
    /**
     * 上传文件超过 multipart 限制。
     *
     * <p>这是 Tomcat/Spring 在进入 controller 之前就抛的，业务层看不到，
     * 所以必须在这里翻译成 413，否则会落到兜底 500。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过限制: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.PAYLOAD_TOO_LARGE, "上传文件超过大小限制"));
    }

    /** multipart 请求本身不合法（边界缺失、格式错误等）。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e) {
        log.warn("multipart 请求不合法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "上传请求格式错误"));
    }

    /** 缺少 file 参数。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("缺少 multipart 参数: {}", e.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "缺少参数: " + e.getRequestPartName()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开，忽略: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
