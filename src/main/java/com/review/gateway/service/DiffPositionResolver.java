package com.review.gateway.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Diff Position Anchoring (DPR-02): resolves GitLab {@code position} coordinates for a set of
 * LLM-named {@code (file, line)} lookup keys, by parsing the unified diff Gateway already stored at
 * Review-creation time ({@code review_inputs.diff}). A pure function, no DB/network access — mirrors
 * {@link DiffChunker}'s single {@link BufferedReader} line-scan style (never regex-over-the-whole-blob,
 * both for ReDoS avoidance and to bound peak allocation on a ~195KB diff, CSR-01).
 *
 * <p><b>Total function, no exceptions (DPR-02, DPT-01 blocking finding).</b> {@link #resolve} never
 * throws for any input, including {@code null}/empty/binary/adversarial diffs — malformed hunk headers
 * are skipped (not repaired, not thrown on), hunk-header numbers are parsed via a bounded, non-throwing
 * digit scan (never {@code Integer.parseInt} on caller-controlled digits, so a crafted 21-digit header
 * can never raise {@code NumberFormatException}), and every index access is bounds-checked. There is no
 * {@code throw} statement anywhere in this class — {@code GitLabPublisher.buildPositionContext} is the
 * (separately required) outer safety net; this class must never need it.
 *
 * <p><b>New-file (post-change) line interpretation only</b> (design decision, endorsed by the threat
 * model §4.5): a resolved line's key is always the file's <em>new</em> path and <em>new</em> line
 * number. Removed lines and lines outside any hunk are never resolvable — there is deliberately no
 * second "try the old-file interpretation" pass (a second pass would turn a clean "unresolvable" into a
 * coin flip near the top of a file, where both interpretations often validate).
 *
 * <p><b>Path normalization is asymmetric</b> (DPR-05): the leading {@code a/}/{@code b/}/{@code ./}
 * prefix is stripped exactly once from diff-derived paths only (git's own structural artifact) — the
 * caller-supplied {@code wanted} keys are never touched here (an LLM-supplied path is plain text, not a
 * git-decorated one; stripping it too would let a wrong-file collision through, DPT-04). If two distinct
 * diff-derived paths normalize to the same key, the ambiguity is unresolvable by construction (exact
 * match only, no fuzzy fallback) — every entry under that key is dropped rather than guessed.
 */
@Service
public class DiffPositionResolver {

    /**
     * DPR-02: hunk-header numbers are parsed via a bounded digit scan, never {@code Integer.parseInt}.
     * Nine digits caps the parsed value at 999,999,999 — far above any real diff's line count and
     * comfortably below {@code Integer.MAX_VALUE}, so the subsequent per-line saturating-safe long
     * counters (below) can never overflow even on a pathological 100k-line hunk.
     */
    private static final int MAX_HUNK_NUMBER_DIGITS = 9;

    /** The LLM-supplied lookup key: {@code file} name (as sent by the model, unescaped exactly once by
     * the caller — DPR-05) plus a 1-based line number, always interpreted against the new (post-change)
     * file. */
    public record PathLine(String path, int line) {

        /** DPR-15 (SHOULD): {@code path} is LLM-supplied content — never dumped whole into a log/exception rendering. */
        @Override
        public String toString() {
            int pathChars = path == null ? 0 : path.length();
            return "PathLine[path=<masked, " + pathChars + " chars>, line=" + line + "]";
        }
    }

    /**
     * A Gateway-computed, diff-derived position for one new-file line. {@code oldPath} is never
     * {@code null} even for an added file — GitLab's own convention (old_path == new_path, old_line
     * omitted) is applied here, at the source, so {@code /dev/null} never appears in either path
     * (DPR-03). {@code oldLine} is {@code null} for an added line (no old-file counterpart); both
     * {@code oldLine}/{@code newLine} are set for a context line.
     */
    public record ResolvedLine(String oldPath, String newPath, Integer oldLine, Integer newLine) {

        /** DPR-15 (SHOULD): paths and line numbers are diff-derived, MR-author-controlled content —
         * never dumped whole into an accidental log/exception-message rendering. */
        @Override
        public String toString() {
            int oldPathChars = oldPath == null ? 0 : oldPath.length();
            int newPathChars = newPath == null ? 0 : newPath.length();
            return "ResolvedLine[oldPath=<masked, " + oldPathChars + " chars>, newPath=<masked, "
                    + newPathChars + " chars>, oldLine=" + oldLine + ", newLine=" + newLine + "]";
        }
    }

    /** {@code oldCount}/{@code newCount} are the hunk's declared line budgets ({@code @@ -a,b +c,d @@}'s
     * optional {@code ,b}/{@code ,d}, defaulting to 1 per the unified-diff convention when omitted) —
     * F-DP-01: this is what lets {@link #processLine} tell a genuine end-of-hunk from a {@code --- }/
     * {@code +++ }-shaped line that is actually hunk-body content (e.g. a removed SQL/Lua comment). */
    private record HunkHeader(int oldStart, int newStart, long oldCount, long newCount) {
    }

    /**
     * Resolves as many of {@code wanted} as the diff supports. Returns an empty (never {@code null})
     * map for any degenerate input — {@code null}/blank diff, {@code null}/empty {@code wanted} — with
     * zero parsing work in that case.
     */
    public Map<PathLine, ResolvedLine> resolve(String diff, Set<PathLine> wanted) {
        if (diff == null || diff.isEmpty() || wanted == null || wanted.isEmpty()) {
            return Map.of();
        }

        Map<PathLine, ResolvedLine> result = new HashMap<>();
        Map<String, String> firstRawPathByKey = new HashMap<>();
        Set<String> ambiguousKeys = new HashSet<>();
        State state = new State();

        forEachLine(diff, line -> processLine(line, state, wanted, result, firstRawPathByKey, ambiguousKeys));

        if (!ambiguousKeys.isEmpty()) {
            // DPR-05: ambiguous (colliding) diff-derived paths are unresolvable -- drop every entry
            // keyed under them, never guess which original file was meant.
            result.keySet().removeIf(key -> ambiguousKeys.contains(key.path()));
        }
        return Map.copyOf(result);
    }

    private void processLine(String line, State state, Set<PathLine> wanted, Map<PathLine, ResolvedLine> result,
                              Map<String, String> firstRawPathByKey, Set<String> ambiguousKeys) {
        if (line.startsWith("diff --git ")) {
            state.reset();
            return;
        }
        // F-DP-01: header-line recognition is confined to outside an active hunk body (mirrors
        // DiffChunker's CSR-11 confinement, adapted to this class's incremental scan -- DiffChunker
        // knows a section's full line list up front and can look for the first "@@"; this class sees
        // one line at a time, so "active hunk body" is tracked via the hunk's own declared line budget
        // instead). Without this guard, a removed/added line whose content starts with "-- "/"++ "
        // (e.g. a removed SQL/Lua "-- comment", serialized as "--- comment") is misread as a new file
        // header. When the guard holds off (still inside an unexhausted hunk), the line falls through to
        // the ordinary '+'/'-'/' ' dispatch below like any other hunk-body line.
        if (line.startsWith("--- ") && !activeHunkBody(state)) {
            state.oldPathRaw = extractPath(line.substring(4));
            state.oldPathNormalized = state.oldPathRaw == null ? null : normalizeDiffPath(state.oldPathRaw);
            state.inHunk = false;
            return;
        }
        if (line.startsWith("+++ ") && !activeHunkBody(state)) {
            state.newPathRaw = extractPath(line.substring(4));
            if (state.newPathRaw == null) {
                state.newPathNormalized = null;
            } else {
                state.newPathNormalized = normalizeDiffPath(state.newPathRaw);
                registerPath(state.newPathNormalized, state.newPathRaw, firstRawPathByKey, ambiguousKeys);
            }
            state.inHunk = false;
            return;
        }
        if (line.startsWith("@@")) {
            HunkHeader header = parseHunkHeader(line);
            state.inHunk = header != null;
            if (header != null) {
                state.oldLine = (long) header.oldStart();
                state.newLine = (long) header.newStart();
                state.hunkOldRemaining = header.oldCount();
                state.hunkNewRemaining = header.newCount();
            }
            return;
        }
        if (!state.inHunk) {
            return;
        }
        if (line.isEmpty()) {
            // A genuinely empty line inside a hunk region -- unified diff has no zero-character marker,
            // so this can only be an adversarial/truncated input. Treat as a no-op rather than guessing.
            return;
        }
        switch (line.charAt(0)) {
            case '+' -> {
                emitIfWanted(state, wanted, result, null, state.newLine);
                state.newLine = advance(state.newLine);
                state.hunkNewRemaining--;
            }
            case '-' -> {
                state.oldLine = advance(state.oldLine);
                state.hunkOldRemaining--;
            }
            case ' ' -> {
                emitIfWanted(state, wanted, result, state.oldLine, state.newLine);
                state.oldLine = advance(state.oldLine);
                state.newLine = advance(state.newLine);
                state.hunkOldRemaining--;
                state.hunkNewRemaining--;
            }
            case '\\' -> {
                // "\ No newline at end of file" -- no counter movement, still inside the hunk.
            }
            default -> {
                // Any other content shape (never valid inside a hunk) ends it defensively rather than
                // guessing counter movement -- a later "@@" line can still restart resolution.
                state.inHunk = false;
            }
        }
    }

    /** F-DP-01: true while {@code state} is inside a hunk whose declared old/new line budget is not yet
     * fully consumed -- i.e. a {@code --- }/{@code +++ }-shaped line encountered right now is hunk-body
     * content, not a new file header. A budget of zero on both sides (or state.inHunk == false) means
     * the hunk has ended (or never started), so the next such line is free to be read as a header. */
    private boolean activeHunkBody(State state) {
        return state.inHunk && (state.hunkOldRemaining > 0 || state.hunkNewRemaining > 0);
    }

    private void emitIfWanted(State state, Set<PathLine> wanted, Map<PathLine, ResolvedLine> result,
                               Long oldLine, Long newLine) {
        if (state.newPathNormalized == null || newLine == null) {
            return;
        }
        Integer newLineInt = toLineNumber(newLine);
        if (newLineInt == null) {
            return;
        }
        PathLine key = new PathLine(state.newPathNormalized, newLineInt);
        if (!wanted.contains(key)) {
            return;
        }
        // DPR-03: an added file's old side is /dev/null (extractPath already turned that into a null
        // oldPathNormalized) -- GitLab's own convention is old_path == new_path with old_line omitted,
        // applied here so /dev/null can never reach a wire position downstream.
        String oldPath = state.oldPathNormalized != null ? state.oldPathNormalized : state.newPathNormalized;
        Integer oldLineInt = toLineNumber(oldLine);
        result.put(key, new ResolvedLine(oldPath, state.newPathNormalized, oldLineInt, newLineInt));
    }

    /** @return {@code null} (unresolvable) rather than throwing/wrapping if the value doesn't fit an {@code int} line number. */
    private Integer toLineNumber(Long value) {
        if (value == null || value < 1 || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private Long advance(Long counter) {
        return counter == null ? null : counter + 1;
    }

    private HunkHeader parseHunkHeader(String line) {
        int minusIdx = line.indexOf('-');
        int plusIdx = line.indexOf('+');
        if (minusIdx < 0 || plusIdx < 0 || plusIdx < minusIdx) {
            return null;
        }
        NumberScan oldStart = scanLeadingNumber(line, minusIdx + 1);
        NumberScan newStart = scanLeadingNumber(line, plusIdx + 1);
        if (oldStart.value() < 0 || newStart.value() < 0) {
            return null;
        }
        long oldCount = parseOptionalCount(line, oldStart.end());
        long newCount = parseOptionalCount(line, newStart.end());
        if (oldCount < 0 || newCount < 0) {
            // A comma is present but not followed by a well-formed bounded digit run -- malformed either
            // way, reject the whole header rather than guessing a budget (DPR-02).
            return null;
        }
        return new HunkHeader(oldStart.value(), newStart.value(), oldCount, newCount);
    }

    /**
     * Parses the hunk header's optional {@code ,count} suffix (F-DP-01) right after a start number ends
     * at {@code afterStart}. Per the unified-diff convention, an omitted count means exactly 1 line.
     * Returns {@code -1} (malformed, never thrown) if a comma is present but not followed by a valid
     * bounded digit run -- same posture as the start-number scan itself.
     */
    private long parseOptionalCount(String line, int afterStart) {
        if (afterStart >= line.length() || line.charAt(afterStart) != ',') {
            return 1;
        }
        return scanLeadingNumber(line, afterStart + 1).value();
    }

    private record NumberScan(int value, int end) {
    }

    /**
     * Scans the run of ASCII digits starting at {@code from}, returning both the parsed value and the
     * index right after the last digit (so a caller can check for a following {@code ,count}). {@code
     * value} is {@code -1} (malformed, never thrown) if there are no digits or more than
     * {@link #MAX_HUNK_NUMBER_DIGITS} of them — the DPR-02 guard against a crafted
     * {@code @@ -99999999999999999999,1 +1,1 @@} header.
     */
    private NumberScan scanLeadingNumber(String line, int from) {
        int len = line.length();
        int end = from;
        while (end < len && Character.isDigit(line.charAt(end))) {
            end++;
        }
        int digitCount = end - from;
        if (digitCount == 0 || digitCount > MAX_HUNK_NUMBER_DIGITS) {
            // Either no digits at all, or more of them than a real diff could ever need -- malformed
            // either way; the cap is what keeps this a bounded digit scan rather than an unguarded
            // Integer.parseInt on caller-controlled digits (DPR-02).
            return new NumberScan(-1, end);
        }
        int value = 0;
        for (int i = from; i < end; i++) {
            value = value * 10 + (line.charAt(i) - '0');
        }
        return new NumberScan(value, end);
    }

    /**
     * Extracts a header path from the text after {@code "--- "}/{@code "+++ "}. Git may append a
     * {@code \t}-separated timestamp after the path; a real path never contains a tab, so truncating
     * there is safe and defensive. Returns {@code null} for {@code /dev/null} or an empty result.
     */
    private String extractPath(String rest) {
        if (rest == null) {
            return null;
        }
        int tab = rest.indexOf('\t');
        String path = (tab >= 0 ? rest.substring(0, tab) : rest).trim();
        if (path.isEmpty() || path.equals("/dev/null")) {
            return null;
        }
        return path;
    }

    /** DPR-05: strips exactly one leading {@code a/}, {@code b/}, or {@code ./} — diff-derived side only. */
    private String normalizeDiffPath(String rawPath) {
        if (rawPath.startsWith("a/") || rawPath.startsWith("b/") || rawPath.startsWith("./")) {
            return rawPath.substring(2);
        }
        return rawPath;
    }

    private void registerPath(String normalizedKey, String rawPath, Map<String, String> firstRawPathByKey,
                               Set<String> ambiguousKeys) {
        String existing = firstRawPathByKey.putIfAbsent(normalizedKey, rawPath);
        if (existing != null && !existing.equals(rawPath)) {
            ambiguousKeys.add(normalizedKey);
        }
    }

    /**
     * DPR-02: unlike {@code DiffChunker}'s equivalent helper, this one never re-throws — {@code
     * StringReader} never actually raises {@code IOException} in practice, but "no throw statement
     * anywhere in this class" is itself part of the DPR-02 contract, so the (unreachable) checked-
     * exception case degrades to "stop scanning, keep whatever was already resolved" instead of
     * wrapping and propagating.
     */
    private void forEachLine(String text, Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        } catch (IOException neverInPracticeForAStringReader) {
            // Intentionally swallowed -- see javadoc above.
        }
    }

    /** Mutable per-scan state — deliberately package-private-shaped, never exposed outside this class. */
    private static final class State {
        String oldPathRaw;
        String newPathRaw;
        String oldPathNormalized;
        String newPathNormalized;
        boolean inHunk;
        Long oldLine;
        Long newLine;
        /** F-DP-01: the current hunk's remaining declared old/new line budget -- see {@link #activeHunkBody}. */
        long hunkOldRemaining;
        long hunkNewRemaining;

        void reset() {
            oldPathRaw = null;
            newPathRaw = null;
            oldPathNormalized = null;
            newPathNormalized = null;
            inHunk = false;
            oldLine = null;
            newLine = null;
            hunkOldRemaining = 0;
            hunkNewRemaining = 0;
        }
    }
}
