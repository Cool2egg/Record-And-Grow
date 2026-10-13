package com.recordandgrow.controller;

import com.recordandgrow.infra.health.HealthService;
import com.recordandgrow.infra.health.HealthStatus;
import com.recordandgrow.infra.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口。
 *
 * <p>真实探活数据库与 pgvector 扩展，数据库不可用时由全局异常处理器返回 503。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatus> health() {
        return ApiResponse.success(healthService.check());
    }
}
