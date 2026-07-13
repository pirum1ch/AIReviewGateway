package com.review.gateway.controller;

import com.review.gateway.dto.CreateReviewRequest;
import com.review.gateway.dto.CreateReviewResponse;
import com.review.gateway.dto.ReviewStatusResponse;
import com.review.gateway.service.ReviewService;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import com.review.gateway.service.dto.ReviewStatusView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CI-facing (and admin-cancel) endpoints for the {@code reviews} resource (architecture §11, req.
 * 1.1/1.4/1.5). {@code POST}/{@code GET} require {@code CI}; {@code DELETE} requires {@code ADMIN}
 * (enforced by {@code SecurityConfig}, not here — this class contains no authorization logic itself).
 */
@RestController
@RequestMapping("/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * Creates a Review, or returns the existing one for the same dedup key (req. 1.5). {@code 201} for
     * a genuinely new Review, {@code 200} when an existing active Review was returned instead (no new
     * resource was created). Oversized diffs surface as {@code 422 DIFF_TOO_LARGE} via
     * {@code GlobalExceptionHandler} (fail-fast at the edge).
     */
    @PostMapping
    public ResponseEntity<CreateReviewResponse> createReview(@Valid @RequestBody CreateReviewRequest request) {
        CreateReviewCommand command = new CreateReviewCommand(
                request.projectId(), request.mergeRequestId(), request.headSha(),
                request.baseSha(), request.diff(), request.promptVersion(), request.priority());
        CreateReviewResult result = reviewService.createReview(command);
        CreateReviewResponse body = new CreateReviewResponse(result.reviewId(), result.status().name());
        HttpStatus status = result.deduplicated() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }

    /** Status read (req. 1.1). {@code 404} via {@code GlobalExceptionHandler} if the id is unknown. */
    @GetMapping("/{id}")
    public ResponseEntity<ReviewStatusResponse> getStatus(@PathVariable("id") Long id) {
        ReviewStatusView view = reviewService.getStatus(id);
        return ResponseEntity.ok(toResponse(view));
    }

    /**
     * Admin cancel (req. 1.4, architecture §4 rows 13-16). {@code 409} via {@code GlobalExceptionHandler}
     * if the Review is already in a terminal state.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ReviewStatusResponse> cancel(@PathVariable("id") Long id) {
        ReviewStatusView view = reviewService.cancel(id);
        return ResponseEntity.ok(toResponse(view));
    }

    private ReviewStatusResponse toResponse(ReviewStatusView view) {
        return new ReviewStatusResponse(view.reviewId(), view.status().name(), view.attempts(),
                view.createdAt(), view.updatedAt(), (int) view.commentCount());
    }
}
