package com.review.gateway.controller;

import com.review.gateway.dto.ErrorResponse;
import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.exception.IncompatiblePromptVersionException;
import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.exception.JobNotClaimableException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceMissingException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import com.review.gateway.exception.PromptTooLargeException;
import com.review.gateway.exception.ReviewNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;

/**
 * Maps domain exceptions to the uniform {@link ErrorResponse} body (architecture §11, SR-17). Every
 * handler here returns only a short machine-readable code and a static/derived message — never a stack
 * trace, exception class name, or any other internal detail. Unexpected exceptions fall through to a
 * generic {@code 500} with a fixed body (the actual exception is logged server-side only).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DiffTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleDiffTooLarge(DiffTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("DIFF_TOO_LARGE", ex.getMessage()));
    }

    @ExceptionHandler(ReviewNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ReviewNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidStateTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INVALID_STATE_TRANSITION", ex.getMessage()));
    }

    @ExceptionHandler(JobNotClaimableException.class)
    public ResponseEntity<ErrorResponse> handleJobNotClaimable(JobNotClaimableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("JOB_NOT_CLAIMABLE", ex.getMessage()));
    }

    @ExceptionHandler(IncompatiblePromptVersionException.class)
    public ResponseEntity<ErrorResponse> handleIncompatiblePromptVersion(IncompatiblePromptVersionException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("PROMPT_VERSION_INCOMPATIBLE_WITH_CHUNKING", ex.getMessage()));
    }

    /**
     * PMR-26: deliberately coarse and undifferentiated — the response body never distinguishes "project
     * not found" / "no access" / "MR not found" / "bad ref" (those would form a cross-project existence
     * oracle under the shared CI token, PMT-08). The full reason is logged server-side by the throwing
     * code and recorded in {@code review_events}; only a fixed, generic message reaches the caller here.
     */
    @ExceptionHandler(PromptSourceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePromptSourceUnavailable(PromptSourceUnavailableException ex) {
        log.warn("Prompt source resolution failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("PROMPT_RESOLUTION_FAILED", "Failed to resolve one or more prompt sources"));
    }

    @ExceptionHandler(PromptSourceMissingException.class)
    public ResponseEntity<ErrorResponse> handlePromptSourceMissing(PromptSourceMissingException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("PROMPT_SOURCE_MISSING", ex.getMessage()));
    }

    @ExceptionHandler(PromptSourceInvalidException.class)
    public ResponseEntity<ErrorResponse> handlePromptSourceInvalid(PromptSourceInvalidException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("PROMPT_SOURCE_INVALID", ex.getMessage()));
    }

    @ExceptionHandler(PromptTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePromptTooLarge(PromptTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("PROMPT_TOO_LARGE", ex.getMessage()));
    }

    /** Postgres SQLSTATE for a genuine detected deadlock (class {@code 40}, "transaction rollback"). */
    private static final String SQLSTATE_DEADLOCK_DETECTED = "40P01";
    /** Postgres SQLSTATE for a serialization failure — same {@code 40} class, same "retry me" contract. */
    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    /**
     * F-DC-08 (revised): the original version of this handler caught {@link
     * DeadlockLoserDataAccessException}, which turned out to be dead code for a real Postgres deadlock in
     * this project. Verified empirically (throwaway probe forcing a genuine two-cycle deadlock, SQLSTATE
     * 40P01, through the actual production repository methods) AND by disassembling this project's own
     * pinned {@code hibernate-core}/{@code spring-orm} jars (do not assume across versions — verify): for
     * Hibernate 6.6.53.Final + {@code PostgreSQLDialect}, SQLSTATE {@code 40P01} is converted to {@code
     * org.hibernate.exception.LockAcquisitionException}, which {@code HibernateJpaDialect} maps to
     * Spring's {@link CannotAcquireLockException} — a <em>sibling</em> of {@code
     * DeadlockLoserDataAccessException} (both extend {@link PessimisticLockingFailureException}, neither
     * extends the other), never the latter. The same disassembly shows SQLSTATE {@code 55P03} (an
     * ordinary bounded {@code lock_timeout} expiry, CSR-19) converts instead to {@code
     * org.hibernate.PessimisticLockException}, which maps to the plain base {@link
     * PessimisticLockingFailureException} — confirmed empirically too (a probe forcing exactly that case
     * observed the plain base type, never {@link CannotAcquireLockException}).
     *
     * <p><b>Defense in depth (appsec's explicit ask):</b> {@code LockTimeoutException} — a Hibernate
     * subtype of {@code LockAcquisitionException} used by some non-Postgres dialects/versions for a
     * lock-timeout condition — would, if this project's Hibernate/dialect version ever changed to route
     * {@code 55P03} through it instead of {@code PessimisticLockException}, also surface here as {@link
     * CannotAcquireLockException} and over-match an ordinary timeout as a "deadlock". So this handler does
     * not trust the exception type alone: {@link #isGenuineDeadlock} walks the cause chain for the actual
     * {@link SQLException#getSQLState()} and only reports {@code DEADLOCK_DETECTED} for the real
     * class-{@code 40} "transaction rollback" codes (deadlock {@code 40P01}, serialization failure {@code
     * 40001} — the same class Hibernate's own generic {@code SQLStateConversionDelegate} groups together);
     * anything else caught here (a hypothetical future version's over-match) still degrades safely to the
     * generic {@code LOCK_TIMEOUT} response below, never a wrong "deadlock" label.
     *
     * <p>Both outcomes remain a bounded {@code 409} (Postgres has already rolled the loser back, so this
     * is always safe to retry, never a {@code 500}) but a genuine deadlock is now distinguishable by error
     * code — letting a test (or an operator dashboard) tell "a real deadlock cycle was detected and
     * broken" (a signal that should be rare/zero post F-DC-03) apart from "this request simply waited too
     * long for a busy row" (an expected, load-dependent outcome). Declared before the broader handler
     * below so Spring's exception-hierarchy resolution picks this one first for the subtype.
     */
    @ExceptionHandler({CannotAcquireLockException.class, DeadlockLoserDataAccessException.class})
    public ResponseEntity<ErrorResponse> handleDeadlock(Exception ex) {
        if (!isGenuineDeadlock(ex)) {
            log.warn("Lock-timeout while processing request (CannotAcquireLockException without a "
                    + "class-40 SQLSTATE root cause -- treated as an ordinary lock timeout, not a deadlock)", ex);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("LOCK_TIMEOUT", "The operation timed out waiting for a database lock; please retry"));
        }
        log.warn("Deadlock detected while processing request; transaction rolled back as the deadlock loser", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DEADLOCK_DETECTED", "The operation was rolled back after a database deadlock; please retry"));
    }

    /**
     * Walks the exception's cause chain looking for the actual JDBC {@link SQLException} and checks its
     * {@code SQLSTATE} directly, rather than trusting the Java exception class alone (see {@link
     * #handleDeadlock} javadoc for why). {@link DeadlockLoserDataAccessException} is, by construction,
     * only ever produced by Spring's error-code translator for a code it already classified as a
     * deadlock — but it is walked the same way for consistency and because Spring's {@code
     * DataAccessException} hierarchy does not itself expose the SQLSTATE.
     */
    private boolean isGenuineDeadlock(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (SQLSTATE_DEADLOCK_DETECTED.equals(sqlState) || SQLSTATE_SERIALIZATION_FAILURE.equals(sqlState)) {
                    return true;
                }
            }
        }
        return ex instanceof DeadlockLoserDataAccessException;
    }

    /**
     * CSR-17: a bounded {@code SET LOCAL lock_timeout} on the claim/cancel/retry transactions can
     * surface as either of these exception types depending on exactly where Postgres's 55P03 error is
     * translated. Mapped to a clean {@code 409}, never a raw {@code 500} — the caller should simply retry
     * shortly. ({@link CannotAcquireLockException} — a subtype of {@link PessimisticLockingFailureException}
     * — and {@link DeadlockLoserDataAccessException} are handled separately above, with a genuine deadlock
     * getting its own, more specific error code — F-DC-08.)
     */
    @ExceptionHandler({QueryTimeoutException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleLockTimeout(Exception ex) {
        log.warn("Lock-timeout while processing request: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("LOCK_TIMEOUT", "The operation timed out waiting for a database lock; please retry"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Request validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MALFORMED_REQUEST", "Request body is missing or malformed"));
    }

    /**
     * A non-numeric {@code @PathVariable} (e.g. {@code GET /reviews/abc}) fails Spring's argument
     * conversion before the controller method runs; without this handler it falls through to the
     * generic 500 handler below instead of a proper 400. Same generic-body format as
     * {@link #handleValidation} — no internal type/conversion detail leaks (SR-15/SR-17).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "a different type";
        String message = ex.getName() + ": must be a valid " + expectedType;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    /**
     * Backstop for anything not explicitly mapped above (SR-17): the real exception is logged
     * server-side (message + type only, never in the response), and the client always gets the same
     * generic body regardless of the underlying cause.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
