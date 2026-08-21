package com.review.gateway.service;

import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import com.review.gateway.service.dto.DiffPosition;
import com.review.gateway.service.dto.DiffRefs;
import com.review.gateway.service.dto.PublishOutcome;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Independent QA verification of Diff Position Anchoring (beyond the developer's own
 * {@code DiffPositionResolverTest}/{@code GitLabPublisherTest}/{@code PublishRetryServiceTest}), driven
 * through the REAL Spring-managed {@link ReviewService}, {@link GitLabPublisher} and
 * {@link PublishRetryService} beans (real {@link DiffPositionResolver}, real diff persisted by a real
 * {@code createReview} call) against a real (Zonky) PostgreSQL instance — only the {@link GitLabClient}
 * HTTP boundary is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class DiffPositionAnchoringEndToEndTest {

    private static final String SHA_A = "a1".repeat(20);
    private static final String SHA_B = "b2".repeat(20);
    private static final String SHA_C = "c3".repeat(20);

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private GitLabPublisher gitLabPublisher;
    @Autowired
    private PublishRetryService publishRetryService;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;

    @MockitoBean
    private GitLabClient gitLabClient;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
    }

    private Review completeReview(long projectId, long mrId, String headSha, String diff) {
        CreateReviewCommand command = new CreateReviewCommand(projectId, mrId, headSha, "base-" + headSha, diff, "v1", 10);
        CreateReviewResult result = reviewService.createReview(command);
        assertThat(result.deduplicated()).isFalse();
        Review review = reviewRepository.findById(result.reviewId()).orElseThrow();
        review.setStatus(ReviewStatus.COMPLETED);
        reviewRepository.saveAndFlush(review);
        return review;
    }

    private ReviewComment persistComment(Review review, String filePath, Integer line, String body) {
        return reviewCommentRepository.saveAndFlush(
                new ReviewComment(review.getId(), filePath, line, Severity.MINOR, body));
    }

    // ---- 2. End-to-end publish flow: multi-file, multi-hunk diff, added file + rename ----

    /**
     * A.java: modified, two hunks (added lines + a context line resolvable in the second hunk).
     * B.java: newly added file (old side is /dev/null).
     * old/C.txt -> new/C.txt: rename with content modification (distinct old/new paths).
     * Plus one comment naming a file absent from the diff (unresolvable) and one comment with no
     * file/line at all (never even attempted for positioning).
     */
    private static final String MULTI_FILE_DIFF =
            "diff --git a/A.java b/A.java\n"
                    + "--- a/A.java\n"
                    + "+++ b/A.java\n"
                    + "@@ -1,3 +1,4 @@\n"
                    + " unchanged1\n"
                    + "-old line\n"
                    + "+new line 2\n"
                    + "+new line 3\n"
                    + " unchanged2\n"
                    + "@@ -20,2 +21,2 @@\n"
                    + " context20\n"
                    + "-old20b\n"
                    + "+new21b\n"
                    + "diff --git a/B.java b/B.java\n"
                    + "new file mode 100644\n"
                    + "--- /dev/null\n"
                    + "+++ b/B.java\n"
                    + "@@ -0,0 +1,2 @@\n"
                    + "+first line\n"
                    + "+second line\n"
                    + "diff --git a/old/C.txt b/new/C.txt\n"
                    + "similarity index 80%\n"
                    + "rename from old/C.txt\n"
                    + "rename to new/C.txt\n"
                    + "--- a/old/C.txt\n"
                    + "+++ b/new/C.txt\n"
                    + "@@ -1,1 +1,1 @@\n"
                    + "-original content\n"
                    + "+renamed content\n";

    @Test
    void multiFileMultiHunkDiffAnchorsResolvableCommentsAndFallsBackForTheRest() {
        Review review = completeReview(1L, 900L, SHA_A, MULTI_FILE_DIFF);

        persistComment(review, "A.java", 2, "resolvable: added line in first hunk");
        persistComment(review, "A.java", 22, "resolvable: added line in second hunk");
        persistComment(review, "B.java", 2, "resolvable: added file, second added line");
        persistComment(review, "new/C.txt", 1, "resolvable: renamed file's new path");
        persistComment(review, "Missing.java", 1, "unresolvable: file not in diff");
        persistComment(review, null, null, "no file/line at all: always plain");

        when(gitLabClient.fetchDiffRefs(review.getProjectId(), review.getMergeRequestId()))
                .thenReturn(Optional.of(new DiffRefs(SHA_B, SHA_C, SHA_A)));
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-id");

        PublishOutcome outcome = gitLabPublisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PUBLISHED);

        // Nothing was dropped: every comment ends up published, whether anchored or plain.
        List<ReviewComment> allComments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(allComments).hasSize(6);
        assertThat(allComments).allSatisfy(c -> assertThat(c.getPublishedAt()).isNotNull());

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DiffPosition> positionCaptor = ArgumentCaptor.forClass(DiffPosition.class);
        Mockito.verify(gitLabClient, Mockito.times(6))
                .postDiscussion(eq(review.getProjectId()), eq(review.getMergeRequestId()),
                        bodyCaptor.capture(), positionCaptor.capture());

        List<String> bodies = bodyCaptor.getAllValues();
        List<DiffPosition> positions = positionCaptor.getAllValues();

        DiffPosition aJava2 = positionOf(bodies, positions, "resolvable: added line in first hunk");
        assertThat(aJava2.newPath()).isEqualTo("A.java");
        assertThat(aJava2.oldPath()).isEqualTo("A.java"); // modified (not added) file -- real old path, not /dev/null
        assertThat(aJava2.newLine()).isEqualTo(2);
        assertThat(aJava2.oldLine()).isNull(); // added line -- old_line omitted

        DiffPosition aJava22 = positionOf(bodies, positions, "resolvable: added line in second hunk");
        assertThat(aJava22.newPath()).isEqualTo("A.java");
        assertThat(aJava22.newLine()).isEqualTo(22);

        DiffPosition bJava = positionOf(bodies, positions, "resolvable: added file, second added line");
        assertThat(bJava.newPath()).isEqualTo("B.java");
        assertThat(bJava.oldPath()).isEqualTo("B.java"); // added file -- old_path == new_path, never /dev/null
        assertThat(bJava.newLine()).isEqualTo(2);
        assertThat(bJava.oldLine()).isNull();

        DiffPosition renamed = positionOf(bodies, positions, "resolvable: renamed file's new path");
        assertThat(renamed.newPath()).isEqualTo("new/C.txt");
        assertThat(renamed.oldPath()).isEqualTo("old/C.txt"); // genuine rename: distinct old/new paths
        assertThat(renamed.newLine()).isEqualTo(1);

        assertThat(positionOf(bodies, positions, "unresolvable: file not in diff")).isNull();
        assertThat(positionOf(bodies, positions, "no file/line at all: always plain")).isNull();
    }

    private DiffPosition positionOf(List<String> bodies, List<DiffPosition> positions, String body) {
        int idx = bodies.indexOf(body);
        assertThat(idx).as("comment body '%s' was posted", body).isGreaterThanOrEqualTo(0);
        return positions.get(idx);
    }

    // ---- 3. Graceful degradation when GitLab's diff_refs lookup misbehaves ----

    @Test
    void diffRefsUnavailableStillPublishesAsPlainNotesAndReachesPublished() {
        Review review = completeReview(1L, 901L, SHA_A, MULTI_FILE_DIFF);
        persistComment(review, "A.java", 2, "would resolve if diff_refs were available");

        when(gitLabClient.fetchDiffRefs(any(), any())).thenReturn(Optional.empty());
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-id");

        PublishOutcome outcome = gitLabPublisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        Mockito.verify(gitLabClient).postDiscussion(any(), any(), any(), isNull());
    }

    // ---- 4. DPT-01 regression: a poisoned diff must not block other Reviews in the same pass ----

    /** The exact adversarial hunk header cited as DPT-01's trigger by the threat model. */
    private static final String POISONED_DIFF =
            "diff --git a/Poison.java b/Poison.java\n"
                    + "--- a/Poison.java\n"
                    + "+++ b/Poison.java\n"
                    + "@@ -99999999999999999999,1 +1,1 @@\n"
                    + "+poisoned line\n";

    @Test
    void poisonedDiffAtTheHeadOfThePassDoesNotBlockLaterCleanReviews() throws InterruptedException {
        // Real (un-mocked) DiffPositionResolver: DPR-02 says the adversarial hunk header must not throw
        // and simply yields no resolvable position for this file -- confirmed below the poisoned Review
        // still reaches PUBLISHED (via a plain note), independently of DPR-01's guard even mattering here.
        Review poisoned = completeReview(1L, 902L, SHA_A, POISONED_DIFF);
        persistComment(poisoned, "Poison.java", 1, "on the poisoned diff");
        Thread.sleep(5); // guarantee createdAt strictly precedes the next Review (createdAt ASC ordering)

        Review clean1 = completeReview(1L, 903L, SHA_B, MULTI_FILE_DIFF);
        persistComment(clean1, "A.java", 2, "clean review 1");
        Thread.sleep(5);

        Review clean2 = completeReview(1L, 904L, SHA_C, MULTI_FILE_DIFF);
        persistComment(clean2, "A.java", 2, "clean review 2");

        when(gitLabClient.fetchDiffRefs(any(), eq(902L))).thenReturn(Optional.of(new DiffRefs(SHA_B, SHA_C, SHA_A)));
        when(gitLabClient.fetchDiffRefs(any(), eq(903L))).thenReturn(Optional.of(new DiffRefs(SHA_A, SHA_C, SHA_B)));
        when(gitLabClient.fetchDiffRefs(any(), eq(904L))).thenReturn(Optional.of(new DiffRefs(SHA_A, SHA_B, SHA_C)));
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-id");

        int publishedCount = publishRetryService.retryPublications();

        assertThat(publishedCount)
                .as("all three Reviews in the pass must publish -- the poisoned one at the head of the "
                        + "createdAt-ASC-ordered candidate list must not abort the pass for the rest (DPT-01/DPR-01b)")
                .isEqualTo(3);
        assertThat(reviewRepository.findById(poisoned.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviewRepository.findById(clean1.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviewRepository.findById(clean2.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PUBLISHED);

        // The poisoned comment itself: no position (malformed header -> no lines resolved for that file),
        // but still published as a plain note, never dropped.
        Mockito.verify(gitLabClient).postDiscussion(any(), any(), eq("on the poisoned diff"), isNull());
    }

    // ---- 5. Non-regression: a Review racing to OBSOLETE mid-publish must not finalize as PUBLISHED ----

    @Test
    void reviewGoingObsoleteMidPublishIsNotFinalizedAsPublished() {
        Review review = completeReview(1L, 905L, SHA_A, MULTI_FILE_DIFF);
        persistComment(review, "A.java", 2, "will publish, but the Review races to OBSOLETE meanwhile");

        when(gitLabClient.fetchDiffRefs(any(), any())).thenReturn(Optional.of(new DiffRefs(SHA_B, SHA_C, SHA_A)));
        // Simulate a concurrent "new head_sha arrived" transition landing between the per-comment publish
        // and GitLabPublisher's finalizePublished re-check -- exactly the race finalizePublished's own
        // fresh re-fetch-and-re-check-status is defending against (pre-existing behavior, not new to this
        // feature; independently re-verified here since Diff Position Anchoring adds a second read of the
        // Review's diff/headSha into the same publish attempt).
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenAnswer(invocation -> {
            Review racing = reviewRepository.findById(review.getId()).orElseThrow();
            racing.setStatus(ReviewStatus.OBSOLETE);
            reviewRepository.saveAndFlush(racing);
            return "discussion-id";
        });

        PublishOutcome outcome = gitLabPublisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PARTIAL);
        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("finalizePublished must not overwrite the raced-in OBSOLETE status back to PUBLISHED")
                .isEqualTo(ReviewStatus.OBSOLETE);
        // The comment itself was still genuinely published to GitLab (idempotency: a retry must not
        // re-post it) even though the Review as a whole did not finalize.
        assertThat(reviewCommentRepository.findById(
                        reviewCommentRepository.findByReviewId(review.getId()).get(0).getId())
                .orElseThrow().getPublishedAt()).isNotNull();
    }
}
