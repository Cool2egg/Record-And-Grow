package com.recordandgrow.controller;

import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.exception.GlobalExceptionHandler;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.timeline.TimelineEntryService;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 时间线接口测试：验证 HTTP 契约（路径映射、状态码、响应信封、参数校验）。
 * Service 用 Mockito 打桩，不碰数据库。
 */
@ExtendWith(MockitoExtension.class)
class TimelineControllerTest {

    private static final OffsetDateTime START = OffsetDateTime.of(2026, 10, 14, 7, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final OffsetDateTime END = OffsetDateTime.of(2026, 10, 14, 7, 30, 0, 0, ZoneOffset.ofHours(8));

    @Mock
    private TimelineEntryService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // LocalValidatorFactoryBean 同时实现 org.springframework.validation.Validator 与 jakarta.validation.Validator
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new TimelineController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createShouldReturnCreatedEntry() throws Exception {
        when(service.create(any())).thenReturn(response(1L, "早上跑步 5 公里"));

        mockMvc.perform(post("/api/timeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"早上跑步 5 公里","startTime":"2026-10-14T07:00:00+08:00","endTime":"2026-10-14T07:30:00+08:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.content").value("早上跑步 5 公里"));
    }

    @Test
    void createShouldReturn400WhenContentBlank() throws Exception {
        mockMvc.perform(post("/api/timeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"  ","startTime":"2026-10-14T07:00:00+08:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("content")));
    }

    @Test
    void createShouldReturn400WhenStartTimeMissing() throws Exception {
        mockMvc.perform(post("/api/timeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"早上跑步 5 公里"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("startTime")));
    }

    @Test
    void getShouldReturnEntry() throws Exception {
        when(service.get(1L)).thenReturn(response(1L, "早上跑步 5 公里"));

        mockMvc.perform(get("/api/timeline/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void getShouldReturn404WhenNotFound() throws Exception {
        when(service.get(999L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "时间线记录不存在"));

        mockMvc.perform(get("/api/timeline/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("时间线记录不存在"));
    }

    @Test
    void listShouldReturnPage() throws Exception {
        PageResult<TimelineEntryResponse> page = PageResult.of(List.of(response(1L, "早上跑步 5 公里")), 1, 1, 20);
        when(service.list(any(), any(), eq(1), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/timeline")
                        .param("start", "2026-10-14T00:00:00+08:00")
                        .param("end", "2026-10-15T00:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].content").value("早上跑步 5 公里"));
    }

    @Test
    void listShouldReturn400WhenStartMalformed() throws Exception {
        mockMvc.perform(get("/api/timeline").param("start", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("start")));
    }

    @Test
    void listShouldReturn503WhenDatabaseUnavailable() throws Exception {
        when(service.list(any(), any(), eq(1), eq(20)))
                .thenThrow(new DataAccessResourceFailureException("无法获取数据库连接"));

        mockMvc.perform(get("/api/timeline"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.DB_UNAVAILABLE.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.DB_UNAVAILABLE.getMessage()));
    }

    @Test
    void updateShouldReturnUpdatedEntry() throws Exception {
        when(service.update(eq(1L), any())).thenReturn(response(1L, "改过的内容"));

        mockMvc.perform(put("/api/timeline/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"改过的内容","startTime":"2026-10-14T07:00:00+08:00","endTime":"2026-10-14T07:30:00+08:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").value("改过的内容"));
    }

    @Test
    void updateShouldReturn404WhenNotFound() throws Exception {
        when(service.update(eq(999L), any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "时间线记录不存在"));

        mockMvc.perform(put("/api/timeline/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"改过的内容","startTime":"2026-10-14T07:00:00+08:00"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    void deleteShouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/timeline/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void deleteShouldReturn404WhenNotFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "时间线记录不存在"))
                .when(service).delete(anyLong());

        mockMvc.perform(delete("/api/timeline/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()));
    }

    private TimelineEntryResponse response(Long id, String content) {
        TimelineEntryResponse resp = new TimelineEntryResponse();
        resp.setId(id);
        resp.setContent(content);
        resp.setStartTime(START);
        resp.setEndTime(END);
        resp.setCreatedAt(START);
        resp.setUpdatedAt(START);
        return resp;
    }
}
