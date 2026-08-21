package com.review.gateway.repository;

import com.review.gateway.model.ReviewPromptSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for the immutable, append-only {@link ReviewPromptSection} rows (architecture §6). No
 * update/delete methods are exposed here by design — PMR-07 requires the app DB user itself to hold
 * only {@code INSERT}/{@code SELECT} on this table (see {@code DEPLOYMENT.md}), and this repository's
 * Java-level surface mirrors that: {@link JpaRepository#save} is used only for the initial insert
 * (never re-saved after that), and no derived {@code deleteBy...}/{@code updateBy...} query exists.
 */
public interface ReviewPromptSectionRepository extends JpaRepository<ReviewPromptSection, Long> {

    /** Claim-time read (§8): all sections of one Review, in assembly order. */
    List<ReviewPromptSection> findByReviewIdOrderByOrdinalAsc(Long reviewId);
}
