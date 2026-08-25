package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured Review Output (architecture §6, SRO-50-57; threat model SOR-09/10/12, all CRITICAL):
 * assembles the published comment body for a structured (v3) finding from a <b>fixed, Gateway-owned
 * template</b> — header + prose + optional {@code ```diff} context block + optional suggestion block.
 * Used only by {@code StructuredResponseParser}; v1/v2 comment bodies (the sanitized comment text alone,
 * via {@link CommentParser}) are completely unchanged.
 *
 * <p>Two sanitization pipelines, deliberately different (SRO-54/55): prose (comment text) goes through
 * {@link CommentParser}'s existing pipeline unchanged, plus (F-SRO-07, appsec SAST fix round) a
 * backtick-run collapse applied here — never inside {@code CommentParser} itself, which must stay
 * byte-identical for v1/v2; code (diff context, suggestion) goes through {@link #sanitizeCodeBlock},
 * which never HTML-escapes (escaping corrupts code inside a fence and protects nothing there — entities
 * are not decoded in a CommonMark fenced block) but does strip control/format characters, collapse
 * backtick runs, and cap length.
 *
 * <p><b>SOR-09 (fence integrity):</b> backtick-run collapsing always runs before any length cap; a block
 * is dropped whole (never truncated internally) in the order diff-context, then suggestion, then the
 * prose itself; and the fully assembled body is structurally verified (balanced fence markers) before
 * this class ever returns it — a body that fails verification drops every code block rather than
 * shipping something broken.
 */
@Service
public class CommentRenderer {

    private static final Logger log = LoggerFactory.getLogger(CommentRenderer.class);

    /** SRO-55: four backticks — defense in depth against a 3-backtick run surviving inside content. */
    private static final String CODE_FENCE = "````";
    private static final String DIFF_FENCE_LANGUAGE = "diff";
    /** SRO-52: a hard rule, never the fence language "suggestion" (GitLab's native apply-syntax). */
    private static final String SUGGESTED_FIX_LABEL = "Suggested fix:";

    private static final Pattern BACKTICK_RUN = Pattern.compile("`{3,}");
    /** SRO-56: a leading '/' not followed by '/' or '*' -- preserves ordinary comment-opening code lines. */
    private static final Pattern QUICK_ACTION_LINE = Pattern.compile("^\\s*/(?![/*]).*$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private final CommentParser commentParser;
    private final TextSanitizer textSanitizer;
    private final GatewayProperties properties;

    public CommentRenderer(CommentParser commentParser, TextSanitizer textSanitizer, GatewayProperties properties) {
        this.commentParser = commentParser;
        this.textSanitizer = textSanitizer;
        this.properties = properties;
    }

    /**
     * @param filePath   the exact, already-SRO-65-validated key (as it appears in {@code
     *                   review_chunks.file_paths} / the {@code diff --git} line) — used both for the
     *                   header (after {@link CommentParser#sanitizeFilePath}) and for locating the
     *                   diff-context section; never anything from the model's response
     * @param lineNumber already {@link CommentParser#normalizeLineNumber}-normalized (SRO-23); {@code
     *                   null} means a file-level finding (no {@code :line}, no diff-context block)
     * @param chunkDiff  {@code review_chunks.diff} for the (reviewId, chunkIndex) of the <b>locked job
     *                   row</b> (SOR-12) — never anything derived from the model's response
     * @return the fully assembled, sanitized, length-bounded, fence-verified comment body
     */
    public String render(String filePath, Integer lineNumber, Severity severity, String rawComment,
                          String rawSuggestion, String chunkDiff) {
        return renderIndexed(filePath, lineNumber, severity, rawComment, rawSuggestion, prepareChunkDiffIndex(chunkDiff));
    }

    /**
     * F-SRO-04 (appsec SAST fix round): takes a pre-built {@link ChunkDiffIndex} instead of the raw
     * {@code chunkDiff} string, so a caller rendering many findings for the SAME chunk (the
     * structured-output path, up to 800 findings per result) builds the index exactly once via {@link
     * #prepareChunkDiffIndex} rather than re-splitting/re-scanning the whole chunk diff inside every
     * single render call. Behavior is identical to {@link #render(String, Integer, Severity, String,
     * String, String)} above, which now just delegates here. A distinct method name (rather than an
     * overload on parameter type) deliberately avoids an ambiguous-overload compile error at any call
     * site that passes a literal {@code null} for the last argument.
     */
    public String renderIndexed(String filePath, Integer lineNumber, Severity severity, String rawComment,
                                 String rawSuggestion, ChunkDiffIndex diffIndex) {
        String sanitizedPath = safeHeaderPath(commentParser.sanitizeFilePath(filePath));
        String header = renderHeader(severity, sanitizedPath, lineNumber);
        // F-SRO-07 (appsec SAST fix round): collapse backtick runs in the PROSE too, not only inside code
        // blocks. sanitizeCodeBlock already guarantees code content can never contain a 3+ backtick run,
        // so CODE_FENCE (four backticks) could previously only appear as a genuine pair UNLESS the model
        // put a literal 4+-backtick run in the prose field -- which defeats hasBalancedFences below (an
        // odd fence count drops BOTH code blocks) and, worse, the pre-fix fallback re-assembly never
        // re-verified the result, so the shipped body could still contain the unbalanced fence. Applied
        // here (not inside CommentParser.sanitizeProseText, which must stay byte-identical for v1/v2 per
        // SRO-54/SR-08/SR-09) on the already-sanitized prose, before assembly and before any cap.
        String prose = collapseBacktickRuns(commentParser.sanitizeProseText(rawComment));

        Optional<String> diffBlock = renderDiffContextBlock(diffIndex, filePath, lineNumber);
        Optional<String> suggestionBlock = renderSuggestionBlock(rawSuggestion);

        int maxLength = Math.max(0, properties.getPublish().getMaxCommentLength());

        // SRO-53: truncation drops whole blocks, in this order, before ever truncating prose text.
        String assembled = assemble(header, prose, diffBlock, suggestionBlock);
        if (assembled.length() > maxLength) {
            assembled = assemble(header, prose, Optional.empty(), suggestionBlock);
        }
        if (assembled.length() > maxLength) {
            assembled = assemble(header, prose, Optional.empty(), Optional.empty());
        }
        if (assembled.length() > maxLength) {
            assembled = assembleWithTruncatedProse(header, prose, maxLength);
        }

        // SOR-09: the assembled body is structurally verified before ever being returned -- a body that
        // fails (e.g. a stray fence-shaped sequence inside the untouched prose pipeline, SRO-54) drops
        // every code block rather than shipping something GitLab could render as broken/open Markdown.
        if (!hasBalancedFences(assembled)) {
            log.warn("Assembled structured comment body failed fence-balance verification; dropping all code blocks");
            assembled = assemble(header, prose, Optional.empty(), Optional.empty());
            if (assembled.length() > maxLength) {
                assembled = assembleWithTruncatedProse(header, prose, maxLength);
            }
            // F-SRO-07: re-verify the fallback itself -- with backtick-collapsed prose this should now be
            // structurally impossible to fail, but SOR-09's own rule is "verify, never assume", so the
            // fallback gets the same treatment as the primary assembly rather than being trusted blind.
            if (!hasBalancedFences(assembled)) {
                log.warn("Fallback structured comment body still failed fence-balance verification; "
                        + "truncating prose as a last resort");
                assembled = assembleWithTruncatedProse(header, prose, maxLength);
            }
        }
        return assembled;
    }

    // ---- header (SRO-50, SOR-10) ----

    private String renderHeader(Severity severity, String sanitizedPath, Integer lineNumber) {
        StringBuilder header = new StringBuilder("**").append(severity.name()).append("** — `").append(sanitizedPath).append('`');
        if (lineNumber != null) {
            header.append(':').append(lineNumber);
        }
        return header.toString();
    }

    /**
     * SOR-10 (CRITICAL): asserted <em>independently</em> of the SRO-65 edge-check that is supposed to
     * already guarantee this, so the two controls do not silently depend on each other — a path that
     * somehow still contains a backtick (should be unreachable) has it stripped defensively before ever
     * being placed inside an inline code span, rather than trusting the edge check alone.
     */
    private String safeHeaderPath(String sanitizedPath) {
        if (sanitizedPath == null) {
            return "";
        }
        if (sanitizedPath.indexOf('`') < 0) {
            return sanitizedPath;
        }
        log.warn("Sanitized file path still contained a backtick at render time (length={}) -- the SRO-65 "
                + "edge check should have rejected this; stripping defensively", sanitizedPath.length());
        return sanitizedPath.replace("`", "");
    }

    // ---- suggestion block (SRO-52/55) ----

    private Optional<String> renderSuggestionBlock(String rawSuggestion) {
        if (rawSuggestion == null || rawSuggestion.isBlank()) {
            return Optional.empty();
        }
        String sanitized = sanitizeCodeBlock(rawSuggestion, properties.getStructured().getMaxSuggestionChars());
        if (sanitized.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SUGGESTED_FIX_LABEL + "\n" + CODE_FENCE + "\n" + sanitized + "\n" + CODE_FENCE);
    }

    // ---- diff-context block (SRO-51, SOR-12) ----

    private Optional<String> renderDiffContextBlock(ChunkDiffIndex diffIndex, String filePath, Integer lineNumber) {
        if (!properties.getStructured().isIncludeDiffContext() || lineNumber == null
                || diffIndex == null || !diffIndex.present()) {
            return Optional.empty();
        }
        Optional<String> raw = extractDiffContext(diffIndex, filePath, lineNumber, properties.getStructured().getDiffContextLines());
        if (raw.isEmpty()) {
            log.debug("Diff-context block omitted: could not locate the finding's line within its own "
                    + "file section of the chunk diff (line={})", lineNumber);
            return Optional.empty();
        }
        String sanitized = sanitizeCodeBlock(raw.get(), properties.getPublish().getMaxCommentLength());
        return Optional.of(CODE_FENCE + DIFF_FENCE_LANGUAGE + "\n" + sanitized + "\n" + CODE_FENCE);
    }

    /**
     * F-SRO-04 (appsec SAST fix round): a pre-split, pre-indexed view of a chunk diff — the line split
     * and the scan for {@code diff --git} section-header line positions each happen exactly once, in
     * {@link #prepareChunkDiffIndex}, rather than inside every single {@link #extractDiffContext} call.
     * Before this existed, rendering N findings for the same chunk (up to 800 per structured-output
     * result) re-split and re-scanned the <em>entire</em> chunk diff (up to ~150KB+) N times over, inside
     * a transaction holding a {@code FOR UPDATE} row lock. Carries no state beyond what {@code
     * extractDiffContext} already derived from {@code chunkDiff} before this class existed.
     */
    public static final class ChunkDiffIndex {
        private static final ChunkDiffIndex ABSENT = new ChunkDiffIndex(false, List.of(), List.of());

        private final boolean present;
        private final List<String> lines;
        /** Indices into {@link #lines} of every line starting with {@code "diff --git "}, in order. */
        private final List<Integer> sectionHeaderLineIndices;

        private ChunkDiffIndex(boolean present, List<String> lines, List<Integer> sectionHeaderLineIndices) {
            this.present = present;
            this.lines = lines;
            this.sectionHeaderLineIndices = sectionHeaderLineIndices;
        }

        private boolean present() {
            return present;
        }
    }

    /**
     * Builds a {@link ChunkDiffIndex} for {@code chunkDiff} — call this ONCE per chunk (e.g. once per
     * {@code POST /jobs/{id}/result} submission), then reuse the returned index across every {@link
     * #render(String, Integer, Severity, String, String, ChunkDiffIndex)} call for that same chunk. A
     * {@code null} {@code chunkDiff} produces an index whose diff-context lookups always miss, exactly
     * like the pre-F-SRO-04 {@code chunkDiff == null} short-circuit.
     */
    public ChunkDiffIndex prepareChunkDiffIndex(String chunkDiff) {
        if (chunkDiff == null) {
            return ChunkDiffIndex.ABSENT;
        }
        List<String> lines = splitLines(chunkDiff);
        List<Integer> sectionHeaderLineIndices = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("diff --git ")) {
                sectionHeaderLineIndices.add(i);
            }
        }
        return new ChunkDiffIndex(true, lines, sectionHeaderLineIndices);
    }

    /**
     * SOR-12 (CRITICAL): locates the file's {@code diff --git} section for the exact validated key,
     * walks only <em>that</em> section's {@code @@} hunks tracking new-file line numbers, and stops at
     * the next section header — never a positional guess over the whole chunk text. {@code diffIndex} is
     * built once (per {@link #prepareChunkDiffIndex}) from the caller's {@code review_chunks.diff} for
     * the locked job row's own {@code (reviewId, chunkIndex)}; this method never receives or infers
     * anything from the model's response.
     *
     * @return the context window (±{@code contextLines}), original {@code +}/{@code -}/space prefixes
     *         intact, or empty if the file section or the exact line could not be located
     */
    private Optional<String> extractDiffContext(ChunkDiffIndex diffIndex, String filePath, int lineNumber, int contextLines) {
        List<String> lines = diffIndex.lines;
        int sectionStart = locateSectionStart(diffIndex, filePath);
        if (sectionStart < 0) {
            return Optional.empty();
        }
        int sectionEnd = locateNextSectionStart(diffIndex, sectionStart);

        int i = sectionStart;
        while (i < sectionEnd) {
            Matcher hunkMatch = HUNK_HEADER.matcher(lines.get(i));
            if (!hunkMatch.matches()) {
                i++;
                continue;
            }
            int newLineNumber = Integer.parseInt(hunkMatch.group(1));
            List<Integer> bodyIndices = new ArrayList<>();
            int targetIndex = -1;
            int j = i + 1;
            while (j < sectionEnd && !lines.get(j).startsWith("@@")) {
                String bodyLine = lines.get(j);
                char marker = bodyLine.isEmpty() ? ' ' : bodyLine.charAt(0);
                if (marker == '+' || marker == ' ') {
                    if (newLineNumber == lineNumber) {
                        targetIndex = bodyIndices.size();
                    }
                    newLineNumber++;
                }
                bodyIndices.add(j);
                j++;
            }
            if (targetIndex >= 0) {
                int from = Math.max(0, targetIndex - contextLines);
                int to = Math.min(bodyIndices.size() - 1, targetIndex + contextLines);
                StringBuilder out = new StringBuilder();
                for (int k = from; k <= to; k++) {
                    out.append(lines.get(bodyIndices.get(k))).append('\n');
                }
                return Optional.of(out.toString());
            }
            i = j;
        }
        return Optional.empty();
    }

    private List<String> splitLines(String text) {
        // Deliberately not String.split("\n", -1) directly assigned to a mutable List -- Arrays.asList's
        // result is fixed-size but we only ever read from it here, so that's fine; kept explicit for clarity.
        return List.of(text.split("\n", -1));
    }

    /**
     * F-SRO-04: scans only the pre-collected {@link ChunkDiffIndex#sectionHeaderLineIndices} — a list
     * bounded by the chunk's file count (typically dozens) — rather than every line of the chunk diff
     * (up to ~150KB+).
     */
    private int locateSectionStart(ChunkDiffIndex diffIndex, String filePath) {
        String suffix = " b/" + filePath;
        for (int headerIndex : diffIndex.sectionHeaderLineIndices) {
            if (diffIndex.lines.get(headerIndex).endsWith(suffix)) {
                return headerIndex;
            }
        }
        return -1;
    }

    private int locateNextSectionStart(ChunkDiffIndex diffIndex, int sectionStart) {
        for (int headerIndex : diffIndex.sectionHeaderLineIndices) {
            if (headerIndex > sectionStart) {
                return headerIndex;
            }
        }
        return diffIndex.lines.size();
    }

    // ---- code sanitization (SRO-55/56/57) ----

    /**
     * MUST NOT HTML-escape (entities are not decoded inside a CommonMark fence, so escaping there
     * corrupts code and protects nothing). Strips Cc(except {@code \n}/{@code \t})/Cf/Zl/Zp (reusing
     * {@link TextSanitizer}, not a second implementation of the F-DC-02 character-class lesson), strips
     * SRO-56 quick-action-shaped lines, collapses every run of 3+ backticks <em>before</em> capping
     * length (SOR-09), then caps.
     */
    private String sanitizeCodeBlock(String raw, int maxLength) {
        String controlStripped = textSanitizer.sanitizeSectionText(raw);
        String withoutQuickActions = stripQuickActionLines(controlStripped == null ? "" : controlStripped);
        String backtickCollapsed = collapseBacktickRuns(withoutQuickActions);
        return capLength(backtickCollapsed, maxLength);
    }

    private String collapseBacktickRuns(String text) {
        return BACKTICK_RUN.matcher(text).replaceAll("``");
    }

    /**
     * SRO-56: applied to every line of a code block, including diff-context lines — a diff {@code +}/
     * {@code -} line is inherently safe (a quick action must begin the line), but a context line carries
     * a single leading space, so {@code " /close"} still matches.
     */
    private String stripQuickActionLines(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (QUICK_ACTION_LINE.matcher(line).matches()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    // ---- assembly + truncation (SRO-53, SOR-09) ----

    private String assemble(String header, String prose, Optional<String> diffBlock, Optional<String> suggestionBlock) {
        StringBuilder body = new StringBuilder(header).append("\n\n").append(prose);
        diffBlock.ifPresent(block -> body.append("\n\n").append(block));
        suggestionBlock.ifPresent(block -> body.append("\n\n").append(block));
        return body.toString();
    }

    private String assembleWithTruncatedProse(String header, String prose, int maxLength) {
        int separatorLength = 2; // "\n\n"
        int proseBudget = Math.max(0, maxLength - header.length() - separatorLength);
        return header + "\n\n" + capLength(prose, proseBudget);
    }

    /**
     * SOR-09: cheap, durable structural check -- our own fence marker ({@link #CODE_FENCE}, four
     * backticks) can only ever appear as a genuinely emitted open/close pair, because {@link
     * #sanitizeCodeBlock} already collapses any 3+ backtick run inside content down to two. An odd count
     * means something (most plausibly a stray sequence surviving the untouched prose pipeline, SRO-54)
     * broke that invariant, and the body must not be shipped as assembled.
     */
    private boolean hasBalancedFences(String body) {
        int count = 0;
        int index = 0;
        while ((index = body.indexOf(CODE_FENCE, index)) >= 0) {
            count++;
            index += CODE_FENCE.length();
        }
        return count % 2 == 0;
    }

    private String capLength(String text, int maxLength) {
        int max = Math.max(0, maxLength);
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
