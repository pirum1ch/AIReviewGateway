package com.review.gateway.service;

import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class BackendDispatcherTest {

    private BackendRepository backendRepository;
    private ReviewJobRepository reviewJobRepository;
    private BackendDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        backendRepository = Mockito.mock(BackendRepository.class);
        reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        dispatcher = new BackendDispatcher(backendRepository, reviewJobRepository);
    }

    private Backend activeBackend(String name, int capacity) {
        return new Backend(name, "https://" + name + ".local", "model-x", capacity);
    }

    @Test
    void resolvesAnActiveBackendWithFreeCapacity() {
        Backend backend = activeBackend("mac-mini-1", 2);
        when(backendRepository.findByName("mac-mini-1")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L);

        Optional<Backend> resolved = dispatcher.resolveClaimableBackend("mac-mini-1");

        assertThat(resolved).contains(backend);
    }

    @Test
    void unknownBackendNameReturnsEmpty() {
        when(backendRepository.findByName("ghost")).thenReturn(Optional.empty());

        assertThat(dispatcher.resolveClaimableBackend("ghost")).isEmpty();
    }

    @Test
    void nonActiveBackendReturnsEmpty() {
        Backend backend = activeBackend("mac-mini-2", 2);
        backend.setStatus(BackendStatus.SUSPECT);
        when(backendRepository.findByName("mac-mini-2")).thenReturn(Optional.of(backend));

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-2")).isEmpty();
    }

    @Test
    void backendAtCapacityReturnsEmpty() {
        Backend backend = activeBackend("mac-mini-3", 1);
        when(backendRepository.findByName("mac-mini-3")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L);

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-3")).isEmpty();
    }

    @Test
    void neverThrowsForAnyDeclineReason() {
        // QA-critical regression guard: resolveClaimableBackend must return Optional.empty() for every
        // decline reason, never throw -- throwing here (even if caught by the caller) crosses this
        // method's own transactional-AOP boundary were it ever re-annotated @Transactional, which is
        // exactly what caused the UnexpectedRollbackException bug this contract change fixes.
        when(backendRepository.findByName("anything")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() -> dispatcher.resolveClaimableBackend("anything"))
                .doesNotThrowAnyException();
    }
}
