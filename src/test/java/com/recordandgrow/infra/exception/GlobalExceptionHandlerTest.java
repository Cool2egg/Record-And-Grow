package com.recordandgrow.infra.exception;

import com.recordandgrow.infra.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器单元测试：验证业务异常、参数校验异常、兜底异常的统一翻译。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldHandleBusinessException() {
        BusinessException e = new BusinessException(ErrorCode.NOT_FOUND, "记录不存在");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusinessException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(resp.getBody().getMessage()).isEqualTo("记录不存在");
    }

    @Test
    void shouldHandleValidationException() throws Exception {
        MethodArgumentNotValidException e = buildValidationException("name", "不能为空");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleValidationException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(resp.getBody().getMessage()).contains("name").contains("不能为空");
    }

    @Test
    void shouldHandleUnexpectedException() {
        Exception e = new RuntimeException("boom");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnexpectedException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
    }

    private MethodArgumentNotValidException buildValidationException(String field, String message) throws Exception {
        Object target = new Object();
        MethodParameter parameter = new MethodParameter(
                Object.class.getDeclaredMethod("equals", Object.class), 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        bindingResult.addError(new FieldError("target", field, message));
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }
}
