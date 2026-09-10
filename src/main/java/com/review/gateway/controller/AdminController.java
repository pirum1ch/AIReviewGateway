package com.review.gateway.controller;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.dto.BackendView;
import com.review.gateway.dto.MetricsResponse;
import com.review.gateway.dto.UpsertBackendRequest;
import com.review.gateway.service.BackendRegistryService;
import com.review.gateway.service.StatisticsService;
import com.review.gateway.service.dto.BackendSnapshot;
import com.review.gateway.service.dto.BackendUpsertOutcome;
import com.review.gateway.service.dto.MetricsSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADMIN-only backend registry and lifecycle-metrics endpoints (architecture §11/§2.3/§5, SR-16).
 * {@code POST}/{@code DELETE} are the Backend Self-Registration admin write surface — always builds its
 * response from the persisted entity via the existing url-free {@link BackendView}, never by echoing the
 * request DTO (BSQ-17), so {@code url} can never leak into a response body.
 */
@RestController
public class AdminController {

    private final StatisticsService statisticsService;
    private final BackendRegistryService backendRegistryService;
    private final GatewayProperties properties;

    public AdminController(StatisticsService statisticsService, BackendRegistryService backendRegistryService,
                            GatewayProperties properties) {
        this.statisticsService = statisticsService;
        this.backendRegistryService = backendRegistryService;
        this.properties = properties;
    }

    @GetMapping("/backends")
    public List<BackendView> listBackends() {
        return statisticsService.listBackends().stream().map(this::toView).toList();
    }

    /** Upsert by name (architecture §2.3): {@code 201} on INSERT, {@code 200} on UPDATE. */
    @PostMapping("/backends")
    public ResponseEntity<BackendView> upsertBackend(@Valid @RequestBody UpsertBackendRequest request) {
        BackendUpsertOutcome outcome = backendRegistryService.upsertByAdmin(request);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(toView(statisticsService.snapshotOf(outcome.backend())));
    }

    /** Soft decommission (architecture §5): {@code 200} with the resulting view, {@code 404} if unknown. */
    @DeleteMapping("/backends/{name}")
    public ResponseEntity<BackendView> decommissionBackend(@PathVariable String name) {
        return backendRegistryService.decommission(name)
                .map(backend -> ResponseEntity.ok(toView(statisticsService.snapshotOf(backend))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/metrics")
    public MetricsResponse metrics() {
        MetricsSnapshot snapshot = statisticsService.computeMetrics();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        snapshot.byStatus().forEach((status, count) -> byStatus.put(status.name(), count));
        // PMR-10: gateway.prompt.enabled is exposed here directly from config (not derived from the
        // DB) so an operator can see at a glance whether the kill-switch is currently on.
        return new MetricsResponse(snapshot.total(), byStatus, snapshot.avgQueueMs(),
                snapshot.avgRunMs(), snapshot.totalComments(), snapshot.retries(),
                properties.getPrompt().isEnabled(), snapshot.promptDisabledCount(), snapshot.promptSectionMissingCount(),
                snapshot.ownershipMismatches(), snapshot.workerFailureReportsIgnored(),
                snapshot.legacyParseFallback(), snapshot.structuredValidationFailures(),
                snapshot.structuredConstraintSent(), snapshot.structuredFallbackUsed(),
                snapshot.structuredFieldTruncated(), snapshot.backendAnnounceRejected(),
                snapshot.backendUrlRepointed());
    }

    private BackendView toView(BackendSnapshot snapshot) {
        return new BackendView(snapshot.id(), snapshot.name(), snapshot.model(), snapshot.capacity(),
                snapshot.status().name(), (int) snapshot.running(), snapshot.lastSeen(), snapshot.probeFailedSince(),
                snapshot.announcedBy());
    }
}
