package com.ke.service.analytics;

import com.ke.service.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营养护指标（FR-O05 / 01 §5，Task 33）：GET /api/wb/metrics → 六键（最近 7 天窗口），
 * 仅编辑/运营可见（类级 @PreAuthorize，越权 → 403 envelope，与审核队列同则）。
 * 工作台指标卡渲染属 Task 34，本任务仅 API。
 */
@RestController
@RequestMapping("/api/wb/metrics")
@PreAuthorize("hasAnyRole('EDITOR','OPERATOR')")
public class MetricsController {

    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public ApiResponse<MetricsService.MetricsView> metrics() {
        return ApiResponse.ok(metrics.view());
    }
}
