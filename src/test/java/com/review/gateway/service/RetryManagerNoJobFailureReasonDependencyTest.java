package com.review.gateway.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structured Output Grammar Budget (SGB-06; threat model SOGT-05, SOGB-07/SOGB-INH-2): {@code
 * RetryManager}'s requeue-vs-fail decision must remain {@code attempts}-vs-{@code max-attempts} only,
 * structurally unable to branch on the new {@code CONSTRAINT_REJECTED} reason (or any other {@code
 * JobFailureReason} value) -- {@code QueueManager.reportFailure} resolves the enum for the AUDIT STRING
 * only and passes a plain, already-composed {@code String} into {@link RetryManager#requeueOrFail}. This
 * is a regression test for an absence: it must still be true after this branch as it was before it.
 */
class RetryManagerNoJobFailureReasonDependencyTest {

    @Test
    void retryManagerNeverReferencesJobFailureReasonAnywhereInItsSignatures() {
        Class<?> jobFailureReasonType = com.review.gateway.model.enums.JobFailureReason.class;

        for (Field field : RetryManager.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("RetryManager field '%s' must never be typed as JobFailureReason", field.getName())
                    .isNotEqualTo(jobFailureReasonType);
        }
        for (Constructor<?> constructor : RetryManager.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes())
                    .as("RetryManager constructor must never take a JobFailureReason parameter")
                    .doesNotContain(jobFailureReasonType);
        }
        for (Method method : RetryManager.class.getDeclaredMethods()) {
            assertThat(method.getParameterTypes())
                    .as("RetryManager.%s must never take a JobFailureReason parameter -- the requeue-vs-fail "
                            + "decision is attempts-based only, per attempts >= max-attempts, and must never "
                            + "branch on a Worker-reported failure classification (WOC-24/SOGB-07)", method.getName())
                    .doesNotContain(jobFailureReasonType);
            assertThat(method.getReturnType())
                    .as("RetryManager.%s must never return a JobFailureReason", method.getName())
                    .isNotEqualTo(jobFailureReasonType);
        }
    }
}
