package com.review.gateway.service;

import com.review.gateway.model.Review;

import java.lang.reflect.Field;

/**
 * Test-only helper: {@link Review#getId()} is normally assigned by the persistence provider on
 * insert, which pure-Mockito unit tests never actually perform. Several services key their behavior
 * off a non-null id (e.g. {@code ReviewInput}'s {@code reviewId} is {@code @NotNull}), so tests that
 * exercise those paths need a way to give a plain, never-persisted {@link Review} instance a
 * deterministic id.
 */
final class ReviewTestSupport {

    private ReviewTestSupport() {
    }

    static Review withId(Review review, long id) {
        try {
            Field idField = Review.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(review, id);
            return review;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set Review.id for test setup", e);
        }
    }
}
