package com.review.gateway.controller;

import com.review.gateway.dto.ErrorResponse;
import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.exception.JobNotClaimableException;
import com.review.gateway.exception.ReviewNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
