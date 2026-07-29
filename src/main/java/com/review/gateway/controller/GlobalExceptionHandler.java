package com.review.gateway.controller;

import com.review.gateway.dto.ErrorResponse;
import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.exception.IncompatiblePromptVersionException;
import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.exception.JobNotClaimableException;
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
     * F-DC-08: a genuine Postgres deadlock (SQLSTATE 40P01, one transaction chosen as the "deadlock
     * loser" and rolled back) surfaces via Spring's {@link DeadlockLoserDataAccessException} — which
     * extends {@link PessimisticLockingFailureException}, so before this handler existed it fell into
     * the same generic {@code LOCK_TIMEOUT} bucket as a benign {@code SET LOCAL lock_timeout} expiry
     * (SQLSTATE 55P03). Both remain a bounded {@code 409} (Postgres has already rolled the loser back,
     * so this is always safe to retry, never a {@code 500}) but are now distinguishable by error code —
     * letting a test (or an operator dashboard) tell "a real deadlock cycle was detected and broken" (a
     * signal that should be rare/zero post F-DC-03) apart from "this request simply waited too long for
     * a busy row" (an expected, load-dependent outcome). Declared before the broader handler below so
     * Spring's exception-hierarchy resolution picks this one first for the subtype.
     */
    @ExceptionHandler(DeadlockLoserDataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDeadlock(DeadlockLoserDataAccessException ex) {
        log.warn("Deadlock detected while processing request; transaction rolled back as the deadlock loser", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DEADLOCK_DETECTED", "The operation was rolled back after a database deadlock; please retry"));
    }

    /**
     * CSR-17: a bounded {@code SET LOCAL lock_timeout} on the claim/cancel/retry transactions can
     * surface as any of these three exception types depending on exactly where Postgres's 55P03 error
     * is translated. Mapped to a clean {@code 409}, never a raw {@code 500} — the caller should simply
     * retry shortly. ({@link DeadlockLoserDataAccessException}, a subtype of {@link
     * PessimisticLockingFailureException}, is handled separately above with its own error code — F-DC-08.)
     */
    @ExceptionHandler({QueryTimeoutException.class, PessimisticLockingFailureException.class, CannotAcquireLockException.class})
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
