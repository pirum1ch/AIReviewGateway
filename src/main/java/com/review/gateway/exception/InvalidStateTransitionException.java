package com.review.gateway.exception;

import com.review.gateway.model.enums.ReviewStatus;

/**
 * Thrown by {@code StateMachine} whenever a requested {@code (from, to)} status pair is not one of
 * the legal transitions enumerated in the implementation architecture §4 transition table. Maps to
 * {@code HTTP 409} at the controller layer (feature/03-api-security).
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(ReviewStatus from, ReviewStatus to) {
        super("Illegal Review state transition: " + from + " -> " + to);
    }
}
