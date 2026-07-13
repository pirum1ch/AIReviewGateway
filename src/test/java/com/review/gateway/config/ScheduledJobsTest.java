package com.review.gateway.config;

import com.review.gateway.service.BackendHealthChecker;
import com.review.gateway.service.HeartbeatChecker;
import com.review.gateway.service.PublishRetryService;
import com.review.gateway.service.TimeoutManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ScheduledJobs} delegates each tick to the right feature/02 driver method, and that
 * an exception from any one of them is caught/logged rather than propagating (so it can never
 * de-schedule the recurring task or affect the other three independent jobs).
 */
class ScheduledJobsTest {

    private HeartbeatChecker heartbeatChecker;
    private TimeoutManager timeoutManager;
    private BackendHealthChecker backendHealthChecker;
    private PublishRetryService publishRetryService;
    private ScheduledJobs scheduledJobs;

    @BeforeEach
    void setUp() {
        heartbeatChecker = Mockito.mock(HeartbeatChecker.class);
        timeoutManager = Mockito.mock(TimeoutManager.class);
        backendHealthChecker = Mockito.mock(BackendHealthChecker.class);
        publishRetryService = Mockito.mock(PublishRetryService.class);
        scheduledJobs = new ScheduledJobs(heartbeatChecker, timeoutManager, backendHealthChecker, publishRetryService);
    }

    @Test
    void sweepStaleHeartbeatsDelegatesToHeartbeatChecker() {
        scheduledJobs.sweepStaleHeartbeats();

        verify(heartbeatChecker).sweepStalled();
    }

    @Test
    void sweepStaleHeartbeatsSwallowsExceptions() {
        doThrow(new RuntimeException("boom")).when(heartbeatChecker).sweepStalled();

        assertThatCode(() -> scheduledJobs.sweepStaleHeartbeats()).doesNotThrowAnyException();
    }

    @Test
    void enforceMaxDurationDelegatesToTimeoutManager() {
        scheduledJobs.enforceMaxDuration();

        verify(timeoutManager).enforceMaxDuration();
    }

    @Test
    void enforceMaxDurationSwallowsExceptions() {
        when(timeoutManager.enforceMaxDuration()).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> scheduledJobs.enforceMaxDuration()).doesNotThrowAnyException();
    }

    @Test
    void probeBackendsDelegatesToBackendHealthChecker() {
        scheduledJobs.probeBackends();

        verify(backendHealthChecker).probeAll();
    }

    @Test
    void probeBackendsSwallowsExceptions() {
        when(backendHealthChecker.probeAll()).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> scheduledJobs.probeBackends()).doesNotThrowAnyException();
    }

    @Test
    void retryPublicationsDelegatesToPublishRetryService() {
        scheduledJobs.retryPublications();

        verify(publishRetryService).retryPublications();
    }

    @Test
    void retryPublicationsSwallowsExceptions() {
        when(publishRetryService.retryPublications()).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> scheduledJobs.retryPublications()).doesNotThrowAnyException();
    }

    @Test
    void oneJobsFailureDoesNotPreventTheOthersFromRunning() {
        doThrow(new RuntimeException("boom")).when(heartbeatChecker).sweepStalled();

        scheduledJobs.sweepStaleHeartbeats();
        scheduledJobs.enforceMaxDuration();
        scheduledJobs.probeBackends();
        scheduledJobs.retryPublications();

        verify(timeoutManager).enforceMaxDuration();
        verify(backendHealthChecker).probeAll();
        verify(publishRetryService).retryPublications();
    }
}
