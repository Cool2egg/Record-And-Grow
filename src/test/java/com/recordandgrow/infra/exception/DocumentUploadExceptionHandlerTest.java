package com.recordandgrow.infra.exception;

import com.recordandgrow.infra.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传相关异常的统一翻译：超限 413、格式错误 400、缺参数 400。
 */
class DocumentUploadExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldTranslateMaxUploadSizeTo413() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(13 * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.getCode());
    }

    @Test
    void shouldTranslateMultipartExceptionTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMultipart(new MultipartException("bad boundary"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
    }

    @Test
    void shouldTranslateMissingPartTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMissingPart(new MissingServletRequestPartException("file"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("file");
    }
}
