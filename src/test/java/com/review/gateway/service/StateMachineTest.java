package com.review.gateway.service;

import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.model.Review;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Exhaustively checks the architecture §4 transition table: every legal (from, to) pair is applied
 * and audited, every other pair throws {@link InvalidStateTransitionException} without mutating the
 * Review or writing an event.
 */
class StateMachineTest {

    /** Mirrors the table in {@code StateMachine.buildTransitionTable()} exactly, for cross-checking. */
    private static final Map<ReviewStatus, Set<ReviewStatus>> EXPECTED_LEGAL = buildExpected();

    private static Map<ReviewStatus, Set<ReviewStatus>> buildExpected() {
        Map<ReviewStatus, Set<ReviewStatus>> table = new EnumMap<>(ReviewStatus.class);
        table.put(ReviewStatus.NEW, EnumSet.of(ReviewStatus.QUEUED, ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        table.put(ReviewStatus.QUEUED, EnumSet.of(ReviewStatus.RUNNING, ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        table.put(ReviewStatus.RUNNING, EnumSet.of(
                ReviewStatus.COMPLETED, ReviewStatus.QUEUED, ReviewStatus.FAILED,
                ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        table.put(ReviewStatus.COMPLETED, EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        table.put(ReviewStatus.PUBLISHED, EnumSet.noneOf(ReviewStatus.class));
        table.put(ReviewStatus.FAILED, EnumSet.noneOf(ReviewStatus.class));
        table.put(ReviewStatus.CANCELLED, EnumSet.noneOf(ReviewStatus.class));
        table.put(ReviewStatus.OBSOLETE, EnumSet.noneOf(ReviewStatus.class));
        return table;
    }

    private static Stream<TransitionCase> allPairs() {
        Stream.Builder<TransitionCase> builder = Stream.builder();
        for (ReviewStatus from : ReviewStatus.values()) {
            for (ReviewStatus to : ReviewStatus.values()) {
                boolean legal = EXPECTED_LEGAL.get(from).contains(to);
                builder.add(new TransitionCase(from, to, legal));
            }
        }
        return builder.build();
    }

    private EventService eventService;
    private StateMachine stateMachine;

    @BeforeEach
    void setUp() {
        eventService = Mockito.mock(EventService.class);
        stateMachine = new StateMachine(eventService);
    }

    private Review reviewWithStatus(ReviewStatus status) {
        Review review = new Review(1L, 2L, "sha", "base", "v1", 10);
        review.setStatus(status);
        // Reflection-free id assignment isn't possible (no setId); tests only assert status/event calls.
        return review;
    }

    @ParameterizedTest
    @MethodSource("allPairs")
    void everyPairMatchesTheArchitectureTable(TransitionCase testCase) {
        Review review = reviewWithStatus(testCase.from());

        if (testCase.legal()) {
            stateMachine.transition(review, testCase.to(), EventType.CREATED, "worker-1", 7L, "details");
            assertThat(review.getStatus()).isEqualTo(testCase.to());
            verify(eventService).record(isNull(), eq(EventType.CREATED), eq("worker-1"), eq(7L), eq("details"));
        } else {
            assertThatThrownBy(() -> stateMachine.transition(review, testCase.to(), EventType.CREATED, "worker-1", 7L, "details"))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThat(review.getStatus()).isEqualTo(testCase.from());
            verify(eventService, never()).record(any(), any(), any(), any(), any());
        }
    }

    @Test
    void fourWordOverloadDefaultsWorkerAndBackendToNull() {
        Review review = reviewWithStatus(ReviewStatus.NEW);

        stateMachine.transition(review, ReviewStatus.QUEUED, EventType.CREATED, "created");

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.QUEUED);
        verify(eventService, times(1)).record(isNull(), eq(EventType.CREATED), isNull(), isNull(), eq("created"));
    }

    @Test
    void isLegalReflectsTheSameTable() {
        for (ReviewStatus from : ReviewStatus.values()) {
            for (ReviewStatus to : ReviewStatus.values()) {
                assertThat(stateMachine.isLegal(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED_LEGAL.get(from).contains(to));
            }
        }
    }

    private record TransitionCase(ReviewStatus from, ReviewStatus to, boolean legal) {
        @Override
        public String toString() {
            return from + " -> " + to + (legal ? " (legal)" : " (illegal)");
        }
    }
}
