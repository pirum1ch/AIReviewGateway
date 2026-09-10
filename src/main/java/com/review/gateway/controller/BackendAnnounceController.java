package com.review.gateway.controller;

import com.review.gateway.dto.AnnounceBackendRequest;
import com.review.gateway.dto.AnnounceBackendResponse;
import com.review.gateway.dto.ErrorResponse;
import com.review.gateway.service.BackendRegistryService;
import com.review.gateway.service.MetricsCounters;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker self-registration (architecture §2.2, BSR-10): {@code POST /backends/announce}, WORKER role.
 *
 * <p>Gated behind {@code gateway.backend.self-registration.enabled} via {@link ConditionalOnProperty} on
 * the whole controller — when the flag is off, this route does not exist in the Spring context at all,
 * not merely unauthenticated (BSQ-12: {@code SecurityConfig} independently omits the matcher for this
 * path in that case too, so both fail closed even if the two disagree). This controller contains no
 * authorization logic of its own — {@code SecurityConfig} is the sole place that decides who may reach
 * it — and no field beyond {@code backendId}/{@code workerId}/{@code url}/{@code model} is accepted, so
 * {@code status}/{@code capacity}/mode columns are unreachable from this endpoint by construction.
 */
@RestController
@ConditionalOnProperty(prefix = "gateway.backend.self-registration", name = "enabled", havingValue = "true")
public class BackendAnnounceController {

    private final BackendRegistryService backendRegistryService;
    private final MetricsCounters metricsCounters;

    public BackendAnnounceController(BackendRegistryService backendRegistryService, MetricsCounters metricsCounters) {
        this.backendRegistryService = backendRegistryService;
        this.metricsCounters = metricsCounters;
    }

    @PostMapping("/backends/announce")
    public AnnounceBackendResponse announce(@Valid @RequestBody AnnounceBackendRequest request) {
        return backendRegistryService.announce(request.backendId(), request.workerId(), request.url(), request.model());
    }

    /**
     * BSQ-15: a controller-local handler (takes precedence over {@code GlobalExceptionHandler}'s own
     * {@code MethodArgumentNotValidException} mapping for this one controller only) so a malformed
     * announce body is counted under the {@code VALIDATION} bucket of {@code
     * MetricsCounters#backendAnnounceRejected} — every other endpoint's {@code 400} response shape is
     * untouched. Response body is byte-identical to the shared handler's.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        metricsCounters.incrementBackendAnnounceRejected("VALIDATION");
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Request validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR", message));
    }
}
