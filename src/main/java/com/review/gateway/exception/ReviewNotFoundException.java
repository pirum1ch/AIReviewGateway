package com.review.gateway.exception;

/**
 * Thrown when a Review referenced by id does not exist. Maps to {@code HTTP 404} at the controller
 * layer (feature/03-api-security).
 */
public class ReviewNotFoundException extends RuntimeException {

    public ReviewNotFoundException(Long reviewId) {
        super("Review not found: id=" + reviewId);
    }
}
