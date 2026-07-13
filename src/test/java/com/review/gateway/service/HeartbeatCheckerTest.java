package com.review.gateway.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeartbeatCheckerTest {

    @Test
    void delegatesToTimeoutManagerSweepStaleHeartbeats() {
        TimeoutManager timeoutManager = Mockito.mock(TimeoutManager.class);
        when(timeoutManager.sweepStaleHeartbeats()).thenReturn(2);
        HeartbeatChecker checker = new HeartbeatChecker(timeoutManager);

        checker.sweepStalled();

        verify(timeoutManager).sweepStaleHeartbeats();
    }
}
