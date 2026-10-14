package com.recordandgrow.controller;

import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.exception.GlobalExceptionHandler;
import com.recordandgrow.infra.health.HealthService;
import com.recordandgrow.infra.health.HealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康检查接口测试：验证数据库可用返回 200 UP，不可用返回 503。
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private HealthService healthService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        HealthController controller = new HealthController(healthService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void healthShouldReturnUpWhenDatabaseAvailable() throws Exception {
        when(healthService.check()).thenReturn(HealthStatus.up(true));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").value("UP"))
                .andExpect(jsonPath("$.data.vectorExtension").value(true));
    }

    @Test
    void healthShouldReturn503WhenDatabaseUnavailable() throws Exception {
        when(healthService.check()).thenThrow(new BusinessException(ErrorCode.DB_UNAVAILABLE));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.DB_UNAVAILABLE.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.DB_UNAVAILABLE.getMessage()));
    }
}
