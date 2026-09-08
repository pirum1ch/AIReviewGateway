package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffIntegrityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab Webhook Diff Trigger (threat model §4.2, WHR-12..21, the blocking set): every fail-closed
 * diff-integrity rule, run in this order — reject before assembling, never repair (architecture
 * correction #5):
 * <ol>
 *   <li>whole-MR signals: {@code overflow} (deprecated {@code /changes}) and {@code compare_timeout}
 *       ({@code /repository/compare}) — WHR-16;</li>
 *   <li>per file: path validation before header synthesis — WHR-12;</li>
 *   <li>per file: {@code a_mode}/{@code b_mode} shape — WHR-12;</li>
 *   <li>per file: the WHR-15/15b empty-diff rule (binary marker / same-path mode-only exemption / the
 *       {@code X-Gitlab-Size} HEAD check for new/deleted files) — files that are exempted are excluded
 *       from the returned, coverage-bearing list entirely (WHT-12);</li>
 *   <li>per file, when non-empty: the line-prefix invariant inside every hunk — WHR-13 — and hunk
 *       self-consistency, fail-closed on any parse failure — WHR-14.</li>
 * </ol>
 *
 * <p>Any failure throws {@link DiffIntegrityException} (deterministic — no Review is ever created for
 * this MR/head_sha) or lets a transient {@code DiffFetchUnavailableException} from {@link GitLabClient}
 * propagate unchanged. There is no "sanitize and continue" branch anywhere in this class — deliberately
 * unlike {@link ChunkContextRenderer}'s sanitize-and-continue posture, which operates on already-trusted
 * -shape input; here a silently dropped/renamed file is exactly the failure this class exists to catch.
 */
@Service
public class DiffIntegrityVerifier {

    private static final Logger log = LoggerFactory.getLogger(DiffIntegrityVerifier.class);

    /** Matches {@code ChunkContextRenderer.MAX_PATH_LENGTH} — the existing path-length bound (WHR-12). */
    private static final int MAX_PATH_LENGTH = 300;
    private static final Pattern MODE_PATTERN = Pattern.compile("^[0-7]{6}$");
    private static final Pattern HUNK_HEADER_PATTERN =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private final GitLabClient gitLabClient;
    private final TextSanitizer textSanitizer;
    private final StructuredPathValidator structuredPathValidator;
    private final GatewayProperties properties;

    public DiffIntegrityVerifier(GitLabClient gitLabClient, TextSanitizer textSanitizer,
                                  StructuredPathValidator structuredPathValidator, GatewayProperties properties) {
        this.gitLabClient = gitLabClient;
        this.textSanitizer = textSanitizer;
        this.structuredPathValidator = structuredPathValidator;
        this.properties = properties;
    }

    /**
     * @param structuredPromptVersion whether the Review this diff feeds will use a structured
     *                                 ({@code v3}-family) prompt version — when true, every path must
     *                                 also pass {@link StructuredPathValidator#isEligible}, coherently
     *                                 with {@code ReviewService.validateStructuredOutputEligibility}
     *                                 (SOR-16/17/65 non-regression) rather than failing three layers
     *                                 later with a less specific error.
     * @return the coverage-bearing subset of GitLab's reported files — binary/mode-only-exempt entries
     *         (WHR-15) are never in this list, so they can never appear in the assembled diff's
     *         {@code diff --git} sections and therefore never enter the v3 coverage list (WHT-12)
     * @throws DiffIntegrityException on any deterministic integrity failure (never retried)
     */
    public List<GitLabClient.DiffEntry> verify(Long projectId, Long mergeRequestIid, String baseSha, String headSha,
                                                boolean structuredPromptVersion) {
        // WHR-16: whole-MR signals first -- cheap, and gate the per-file work below.
        boolean overflow = gitLabClient.fetchOverflowFlag(projectId, mergeRequestIid);
        if (overflow) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                    "GitLab reported overflow=true on the deprecated /changes endpoint");
        }
        GitLabClient.CompareResult compare = gitLabClient.compareDiff(projectId, baseSha, headSha);
        if (compare.compareTimeout()) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                    "GitLab reported compare_timeout=true on /repository/compare");
        }

        List<GitLabClient.DiffEntry> coverageBearing = new ArrayList<>();
        for (GitLabClient.DiffEntry entry : compare.files()) {
            if (verifyOneFile(projectId, baseSha, headSha, entry, structuredPromptVersion)) {
                coverageBearing.add(entry);
            }
        }
        log.info("Diff integrity verified: project={} mr={} files={} coverageBearing={}",
                projectId, mergeRequestIid, compare.files().size(), coverageBearing.size());
        return List.copyOf(coverageBearing);
    }

    /** @return {@code true} if {@code entry} belongs in the coverage-bearing set, {@code false} if it is a sound WHR-15 exemption. */
    private boolean verifyOneFile(Long projectId, String baseSha, String headSha, GitLabClient.DiffEntry entry,
                                   boolean structuredPromptVersion) {
        validatePath(entry.oldPath(), structuredPromptVersion);
        validatePath(entry.newPath(), structuredPromptVersion);
        validateMode(entry.aMode());
        validateMode(entry.bMode());

        String diff = entry.diff() == null ? "" : entry.diff();
        boolean binaryMarker = diff.contains("Binary files") && diff.contains("differ");
        boolean modeOnlySamePathExempt = !binaryMarker && diff.isEmpty()
                && entry.aMode() != null && entry.bMode() != null && !entry.aMode().equals(entry.bMode())
                && Objects.equals(entry.oldPath(), entry.newPath())
                && !entry.newFile() && !entry.deletedFile();

        if (binaryMarker || modeOnlySamePathExempt) {
            // WHR-15: the only two sound exemptions -- excluded from the coverage-bearing set entirely.
            return false;
        }

        if (diff.isEmpty()) {
            if (entry.newFile() || entry.deletedFile()) {
                verifyWhr15bSizeCheck(projectId, baseSha, headSha, entry);
                // size == 0 confirmed by the HEAD check above -- genuinely empty file, accept as-is.
            } else {
                throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                        "Empty diff on a file that is not new/deleted, not a binary-marker file, and not a "
                                + "same-path mode-only change (WHR-15)");
            }
        } else {
            validateHunkInvariants(diff);
        }
        return true;
    }

    /**
     * WHR-15b: for a {@code new_file}/{@code deleted_file} entry with an empty {@code diff}, GitLab
     * returns the identical empty string for a genuinely-empty file and for a silently-truncated large
     * one — closed by one cheap {@code HEAD} request read for its {@code X-Gitlab-Size} header (zero
     * response body). {@link GitLabClient#headFileSize} already fails closed (throws
     * {@link DiffIntegrityException}) if the size cannot be verified at all.
     */
    private void verifyWhr15bSizeCheck(Long projectId, String baseSha, String headSha, GitLabClient.DiffEntry entry) {
        String ref = entry.newFile() ? headSha : baseSha;
        String path = entry.newFile() ? entry.newPath() : entry.oldPath();
        long size = gitLabClient.headFileSize(projectId, path, ref);
        if (size > 0) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                    "Empty diff on a new/deleted file whose X-Gitlab-Size HEAD check reported non-zero "
                            + "content -- the diff was silently truncated by GitLab (WHR-15b)");
        }
    }

    /**
     * WHR-12: validate, never repair. {@link TextSanitizer#sanitizePath} is used purely as a detector
     * here ({@code sanitized.equals(raw)} ⇒ pass) — its output is never what gets rendered.
     */
    private void validatePath(String rawPath, boolean structuredPromptVersion) {
        if (rawPath == null || rawPath.isEmpty()) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                    "A file path was missing or empty");
        }
        String sanitized = textSanitizer.sanitizePath(rawPath, MAX_PATH_LENGTH);
        if (sanitized == null || !sanitized.equals(rawPath)) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                    "A file path contains control/format characters, exceeds the length bound, or has "
                            + "incidental leading/trailing whitespace");
        }
        if (rawPath.startsWith("-")) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                    "A file path has a leading '-'");
        }
        if (rawPath.startsWith("diff --git ") || rawPath.startsWith("--- ") || rawPath.startsWith("+++ ")) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                    "A file path collides with a diff-section delimiter literal");
        }
        if (structuredPromptVersion && !structuredPathValidator.isEligible(sanitized, properties.getStructured().getMaxPathChars())) {
            // SOR-16/17/65 non-regression: rejected here, coherently with
            // ReviewService.validateStructuredOutputEligibility, rather than three layers later.
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                    "A file path is not eligible for structured (v3) review output");
        }
    }

    /** WHR-12: {@code a_mode}/{@code b_mode} must be a plain octal file-mode string before rendering. */
    private void validateMode(String mode) {
        if (mode != null && !MODE_PATTERN.matcher(mode).matches()) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                    "a_mode/b_mode did not match the expected ^[0-7]{6}$ shape");
        }
    }

    /**
     * WHR-13 (line-prefix invariant) and WHR-14 (hunk self-consistency), fail-closed: an unparsable
     * header, a content line with no allowed prefix character, or a count mismatch all reject — there is
     * no code path here that skips verification for a hunk it could not parse.
     */
    private void validateHunkInvariants(String diff) {
        String[] lines = diff.split("\n", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && diff.endsWith("\n")) {
            lineCount--; // drop the trailing empty element split("\n", -1) produces for a trailing newline
        }

        boolean foundAnyHunk = false;
        int i = 0;
        while (i < lineCount) {
            Matcher header = HUNK_HEADER_PATTERN.matcher(lines[i]);
            if (!header.matches()) {
                throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                        "Diff body did not start with a valid @@ hunk header, or a content line escaped a "
                                + "hunk boundary");
            }
            foundAnyHunk = true;
            int declaredOld = header.group(2) != null ? Integer.parseInt(header.group(2)) : 1;
            int declaredNew = header.group(4) != null ? Integer.parseInt(header.group(4)) : 1;
            i++;

            int actualOld = 0;
            int actualNew = 0;
            while (i < lineCount && !lines[i].startsWith("@@")) {
                String line = lines[i];
                if (line.isEmpty()) {
                    throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                            "A hunk content line had no unified-diff prefix character (WHR-13)");
                }
                switch (line.charAt(0)) {
                    case ' ' -> {
                        actualOld++;
                        actualNew++;
                    }
                    case '+' -> actualNew++;
                    case '-' -> actualOld++;
                    case '\\' -> {
                        // "\ No newline at end of file" -- counts toward neither side (WHR-14).
                    }
                    default -> throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT,
                            "A hunk content line did not start with ' ', '+', '-' or '\\' (WHR-13)");
                }
                i++;
            }
            if (actualOld != declaredOld || actualNew != declaredNew) {
                throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                        "A hunk's declared line counts did not match its actual content (possible "
                                + "truncation, WHR-14)");
            }
        }
        if (!foundAnyHunk) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                    "Diff body was non-empty but contained no @@ hunk header");
        }
    }
}
