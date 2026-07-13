package com.review.gateway.service;

import com.review.gateway.exception.JobNotClaimableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        Backend resolved = dispatcher.resolveClaimableBackend("mac-mini-1");

        assertThat(resolved).isSameAs(backend);
    }

    @Test
    void unknownBackendNameThrows() {
        when(backendRepository.findByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dispatcher.resolveClaimableBackend("ghost"))
                .isInstanceOf(JobNotClaimableException.class)
                .hasMessageContaining("Unknown backend");
    }

    @Test
    void nonActiveBackendThrows() {
        Backend backend = activeBackend("mac-mini-2", 2);
        backend.setStatus(BackendStatus.SUSPECT);
        when(backendRepository.findByName("mac-mini-2")).thenReturn(Optional.of(backend));

        assertThatThrownBy(() -> dispatcher.resolveClaimableBackend("mac-mini-2"))
                .isInstanceOf(JobNotClaimableException.class)
                .hasMessageContaining("not ACTIVE");
    }

    @Test
    void backendAtCapacityThrows() {
        Backend backend = activeBackend("mac-mini-3", 1);
        when(backendRepository.findByName("mac-mini-3")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L);

        assertThatThrownBy(() -> dispatcher.resolveClaimableBackend("mac-mini-3"))
                .isInstanceOf(JobNotClaimableException.class)
                .hasMessageContaining("at capacity");
    }
}
