package com.review.gateway.service;

import com.review.gateway.model.ReviewEvent;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.repository.ReviewEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies SR-12: {@code details} is scrubbed of token-shaped substrings and length-capped. */
class EventServiceTest {

    private ReviewEventRepository reviewEventRepository;
    private EventService eventService;

    @BeforeEach
    void setUp() {
        reviewEventRepository = Mockito.mock(ReviewEventRepository.class);
        when(reviewEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        eventService = new EventService(reviewEventRepository);
    }

    @Test
    void nullDetailsArePassedThroughUnchanged() {
        eventService.record(1L, EventType.CREATED, null, null, null);

        ArgumentCaptor<ReviewEvent> captor = ArgumentCaptor.forClass(ReviewEvent.class);
        verify(reviewEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDetails()).isNull();
    }

    @Test
    void bearerTokenIsRedacted() {
        eventService.record(1L, EventType.CLAIMED, "worker-1", 2L, "auth header was Bearer abc.def.ghi123 during call");

        ArgumentCaptor<ReviewEvent> captor = ArgumentCaptor.forClass(ReviewEvent.class);
        verify(reviewEventRepository).save(captor.capture());
        String details = captor.getValue().getDetails();
        assertThat(details).doesNotContain("abc.def.ghi123");
        assertThat(details).contains("Bearer [REDACTED]");
    }

    @Test
    void tokenKeyValuePatternsAreRedacted() {
        eventService.record(1L, EventType.CLAIMED, "worker-1", 2L, "token=s3cr3t-value-xyz should never appear");

        ArgumentCaptor<ReviewEvent> captor = ArgumentCaptor.forClass(ReviewEvent.class);
        verify(reviewEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDetails()).doesNotContain("s3cr3t-value-xyz");
    }

    @Test
    void passwordAndSecretAndApiKeyPatternsAreRedacted() {
        eventService.record(1L, EventType.CLAIMED, "worker-1", 2L,
                "password: hunter2, secret=topsecret, apikey=AKIA1234567890");

        ArgumentCaptor<ReviewEvent> captor = ArgumentCaptor.forClass(ReviewEvent.class);
        verify(reviewEventRepository).save(captor.capture());
        String details = captor.getValue().getDetails();
        assertThat(details).doesNotContain("hunter2", "topsecret", "AKIA1234567890");
    }

    @Test
    void overlongDetailsAreTruncated() {
        String longDetails = "x".repeat(2000);

        eventService.record(1L, EventType.HEARTBEAT, "worker-1", 2L, longDetails);

        ArgumentCaptor<ReviewEvent> captor = ArgumentCaptor.forClass(ReviewEvent.class);
        verify(reviewEventRepository).save(captor.capture());
        String details = captor.getValue().getDetails();
        assertThat(details.length()).isLessThan(longDetails.length());
        assertThat(details).endsWith("...(truncated)");
    }

    @Test
    void ordinaryDetailsPassThroughUntouched() {
        eventService.record(1L, EventType.RETRY, "worker-1", 2L, "attempt=2/3");

        ArgumentCaptor<ReviewEvent> captor = ArgumentCaptor.forClass(ReviewEvent.class);
        verify(reviewEventRepository).save(captor.capture());
        assertThat(captor.getValue().getDetails()).isEqualTo("attempt=2/3");
    }
}
