package com.review.gateway.controller;

import com.review.gateway.dto.BackendView;
import com.review.gateway.dto.MetricsResponse;
import com.review.gateway.service.StatisticsService;
import com.review.gateway.service.dto.BackendSnapshot;
import com.review.gateway.service.dto.MetricsSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADMIN-only read endpoints (architecture §11, SR-16): backend registry and lifecycle metrics.
 */
@RestController
public class AdminController {

    private final StatisticsService statisticsService;

    public AdminController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/backends")
    public List<BackendView> listBackends() {
        return statisticsService.listBackends().stream().map(this::toView).toList();
    }

    @GetMapping("/metrics")
    public MetricsResponse metrics() {
        MetricsSnapshot snapshot = statisticsService.computeMetrics();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        snapshot.byStatus().forEach((status, count) -> byStatus.put(status.name(), count));
        return new MetricsResponse(snapshot.total(), byStatus, snapshot.avgQueueMs(),
                snapshot.avgRunMs(), snapshot.totalComments(), snapshot.retries());
    }

    private BackendView toView(BackendSnapshot snapshot) {
        return new BackendView(snapshot.id(), snapshot.name(), snapshot.model(), snapshot.capacity(),
                snapshot.status().name(), (int) snapshot.running(), snapshot.lastSeen());
    }
}
