package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendHealthCheckerTest {

    private BackendRepository backendRepository;
    private BackendProber backendProber;
    private BackendHealthChecker checker;

    @BeforeEach
    void setUp() {
        backendRepository = Mockito.mock(BackendRepository.class);
        backendProber = Mockito.mock(BackendProber.class);
        checker = new BackendHealthChecker(backendRepository, backendProber);
    }

    private Backend backend(String name, BackendStatus status) {
        Backend b = new Backend(name, "https://" + name, "model", 1);
        b.setStatus(status);
        return b;
    }

    @Test
    void activeBackendFlipsToSuspectOnProbeFailure() {
        Backend backend = backend("mac-1", BackendStatus.ACTIVE);
        when(backendRepository.findByStatus(BackendStatus.ACTIVE)).thenReturn(List.of(backend));
        when(backendRepository.findByStatus(BackendStatus.SUSPECT)).thenReturn(List.of());
        doThrow(new BackendUnavailableException("timeout")).when(backendProber).probe(backend);

        int flips = checker.probeAll();

        assertThat(flips).isEqualTo(1);
        assertThat(backend.getStatus()).isEqualTo(BackendStatus.SUSPECT);
        verify(backendRepository).save(backend);
    }

    @Test
    void suspectBackendFlipsToActiveOnRecovery() {
        Backend backend = backend("mac-2", BackendStatus.SUSPECT);
        when(backendRepository.findByStatus(BackendStatus.ACTIVE)).thenReturn(List.of());
        when(backendRepository.findByStatus(BackendStatus.SUSPECT)).thenReturn(List.of(backend));
        doNothing().when(backendProber).probe(backend);

        int flips = checker.probeAll();

        assertThat(flips).isEqualTo(1);
        assertThat(backend.getStatus()).isEqualTo(BackendStatus.ACTIVE);
    }

    @Test
    void healthyActiveBackendStaysActive() {
        Backend backend = backend("mac-3", BackendStatus.ACTIVE);
        when(backendRepository.findByStatus(BackendStatus.ACTIVE)).thenReturn(List.of(backend));
        when(backendRepository.findByStatus(BackendStatus.SUSPECT)).thenReturn(List.of());
        doNothing().when(backendProber).probe(backend);

        int flips = checker.probeAll();

        assertThat(flips).isZero();
        assertThat(backend.getStatus()).isEqualTo(BackendStatus.ACTIVE);
    }

    @Test
    void stillFailingSuspectBackendStaysSuspect() {
        Backend backend = backend("mac-4", BackendStatus.SUSPECT);
        when(backendRepository.findByStatus(BackendStatus.ACTIVE)).thenReturn(List.of());
        when(backendRepository.findByStatus(BackendStatus.SUSPECT)).thenReturn(List.of(backend));
        doThrow(new BackendUnavailableException("still down")).when(backendProber).probe(backend);

        int flips = checker.probeAll();

        assertThat(flips).isZero();
        assertThat(backend.getStatus()).isEqualTo(BackendStatus.SUSPECT);
    }

    @Test
    void unexpectedExceptionFromProberIsTreatedAsUnhealthy() {
        Backend backend = backend("mac-5", BackendStatus.ACTIVE);
        when(backendRepository.findByStatus(BackendStatus.ACTIVE)).thenReturn(List.of(backend));
        when(backendRepository.findByStatus(BackendStatus.SUSPECT)).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(backendProber).probe(backend);

        int flips = checker.probeAll();

        assertThat(flips).isEqualTo(1);
        assertThat(backend.getStatus()).isEqualTo(BackendStatus.SUSPECT);
    }
}
