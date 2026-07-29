package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffTooLargeException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits an oversized unified diff into file-based chunks (§2). A pure function over its input: no
 * DB access, no persisted state — only reads {@link GatewayProperties} (already-loaded config) to
 * compute the per-chunk budget, exactly like {@link DiffSizeValidator}.
 *
 * <p><b>Line-scan only</b> ({@code String.startsWith}/index-based via {@link BufferedReader#readLine()}
 * — never {@code String.split} or a regex over the whole diff): avoids both a ReDoS surface and the
 * large intermediate array a full-blob {@code split} would allocate on a 750KB+ diff.
 */
@Service
public class DiffChunker {

    /** One file-based slice of the diff being split, plus its (already header-region-restricted) file paths. */
    public record DiffChunk(int index, String diff, int estimatedTokens, List<String> filePaths) {
    }

    /** The full result of {@link #split}. */
    public record ChunkPlan(List<DiffChunk> chunks, int totalEstimatedTokens) {
    }

    private final GatewayProperties properties;
    private final DiffSizeValidator diffSizeValidator;

    public DiffChunker(GatewayProperties properties, DiffSizeValidator diffSizeValidator) {
        this.properties = properties;
        this.diffSizeValidator = diffSizeValidator;
    }

    /**
     * @throws DiffTooLargeException if bin-packing would need more than {@code gateway.diff.max-chunks}
     *                                 chunks, or if a single file's diff (even split at hunk boundaries,
     *                                 with its header replayed) still can't fit in one chunk
     */
    public ChunkPlan split(String diff) {
        String effectiveDiff = diff == null ? "" : diff;
        int wholeBudgetTokens = Math.max(1, diffSizeValidator.budgetTokens());
        int estimatedWhole = diffSizeValidator.estimateTokens(effectiveDiff);

        ParsedDiff parsed = parseSections(effectiveDiff);

        // Single-chunk shortcut (§2): if the whole diff already fits the (un-reduced) per-request
        // budget, no context header will ever be rendered (ChunkContextRenderer only fires when
        // chunkCount > 1) -- so use the FULL budget here, not the header-reserved one, and return the
        // original diff String instance unmodified. This is what guarantees byte-identical behavior
        // for small MRs (backward compatibility, §8).
        if (estimatedWhole <= wholeBudgetTokens) {
            List<String> filePaths = parsed.pathsTrusted() ? collectAllFilePaths(parsed.sections()) : List.of();
            DiffChunk single = new DiffChunk(0, diff, estimatedWhole, filePaths);
            return new ChunkPlan(List.of(single), estimatedWhole);
        }

        int charsPerToken = Math.max(1, properties.getDiff().getCharsPerToken());
        int headerReserveTokens = Math.max(0, properties.getDiff().getChunkHeaderReserveTokens());
        int perChunkBudgetTokens = Math.max(1, wholeBudgetTokens - headerReserveTokens);
        int perChunkBudgetChars = perChunkBudgetTokens * charsPerToken;

        List<DiffChunk> chunks = binPack(parsed.sections(), parsed.pathsTrusted(), perChunkBudgetChars);

        int maxChunks = Math.max(1, properties.getDiff().getMaxChunks());
        if (chunks.size() > maxChunks) {
            throw new DiffTooLargeException("Diff requires " + chunks.size() + " chunks, exceeding gateway.diff.max-chunks="
                    + maxChunks);
        }

        int total = chunks.stream().mapToInt(DiffChunk::estimatedTokens).sum();
        return new ChunkPlan(chunks, total);
    }

    // ---- bin-packing (next-fit, original file order preserved) ----

    private List<DiffChunk> binPack(List<Section> sections, boolean pathsTrusted, int perChunkBudgetChars) {
        List<DiffChunk> result = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        Set<String> currentPaths = new LinkedHashSet<>();
        int currentChars = 0;
        int[] chunkIndex = {0};

        for (Section section : sections) {
            String sectionText = section.fullText();
            int sectionChars = sectionText.length();

            if (sectionChars > perChunkBudgetChars) {
                if (currentChars > 0) {
                    result.add(flush(chunkIndex, currentText, currentPaths));
                    currentText = new StringBuilder();
                    currentPaths = new LinkedHashSet<>();
                    currentChars = 0;
                }
                for (String piece : splitOversizedSection(section, perChunkBudgetChars)) {
                    result.add(new DiffChunk(chunkIndex[0]++, piece,
                            diffSizeValidator.estimateTokens(piece),
                            pathsTrusted ? List.copyOf(section.filePaths()) : List.of()));
                }
                continue;
            }

            if (currentChars > 0 && currentChars + sectionChars > perChunkBudgetChars) {
                result.add(flush(chunkIndex, currentText, currentPaths));
                currentText = new StringBuilder();
                currentPaths = new LinkedHashSet<>();
                currentChars = 0;
            }

            currentText.append(sectionText);
            currentChars += sectionChars;
            if (pathsTrusted) {
                currentPaths.addAll(section.filePaths());
            }
        }
        if (currentChars > 0) {
            result.add(flush(chunkIndex, currentText, currentPaths));
        }
        return result;
    }

    private DiffChunk flush(int[] chunkIndex, StringBuilder text, Set<String> paths) {
        String diffText = text.toString();
        return new DiffChunk(chunkIndex[0]++, diffText, diffSizeValidator.estimateTokens(diffText), List.copyOf(paths));
    }

    /**
     * Splits one oversized file section at {@code @@} hunk boundaries only (never inside a hunk),
     * replaying the section's header (everything up to the first hunk) at the top of each piece so
     * every piece is a self-describing, valid-shaped unified diff.
     *
     * @throws DiffTooLargeException naming the file if even one hunk plus the replayed header exceeds
     *                                the per-chunk budget
     */
    private List<String> splitOversizedSection(Section section, int perChunkBudgetChars) {
        String header = section.headerText();
        List<String> hunks = section.hunkTexts();
        if (hunks.isEmpty()) {
            // No hunk markers at all (e.g. a pure rename/mode-change with no content, or a single
            // indivisible blob with no recognizable diff structure at all) -- nothing to split on. If it
            // still doesn't fit, this is exactly the "even the whole indivisible piece exceeds budget"
            // case the fallback guard must reject rather than silently dispatch an oversized chunk.
            String whole = section.fullText();
            if (whole.length() > perChunkBudgetChars) {
                throw new DiffTooLargeException("File '" + section.primaryPath() + "' has no hunk markers to split on "
                        + "and its content alone exceeds the per-chunk budget (size=" + whole.length()
                        + " chars, budget=" + perChunkBudgetChars + " chars)");
            }
            return List.of(whole);
        }

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        boolean currentHasHunk = false;

        for (String hunk : hunks) {
            if (header.length() + hunk.length() > perChunkBudgetChars) {
                throw new DiffTooLargeException("File '" + section.primaryPath() + "' has a hunk that exceeds the "
                        + "per-chunk budget even with its header replayed (size=" + (header.length() + hunk.length())
                        + " chars, budget=" + perChunkBudgetChars + " chars)");
            }
            if (currentHasHunk && current.length() + hunk.length() > perChunkBudgetChars) {
                pieces.add(current.toString());
                current = new StringBuilder(header);
                currentHasHunk = false;
            }
            current.append(hunk);
            currentHasHunk = true;
        }
        if (currentHasHunk) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    // ---- section parsing (line-scan only) ----

    private record ParsedDiff(List<Section> sections, boolean pathsTrusted) {
    }

    /**
     * Parses {@code diff} into file-based sections. Tries git-style {@code diff --git } delimiters
     * first; if none are found, falls back to plain unified-diff {@code --- } delimiters (CSR-11: path
     * provenance is untrusted in that fallback, so paths are never extracted there); if that also
     * yields nothing, the whole input is one indivisible section.
     */
    private ParsedDiff parseSections(String diff) {
        List<Section> gitSections = scanByDelimiter(diff, "diff --git ", true);
        if (!gitSections.isEmpty()) {
            return new ParsedDiff(gitSections, true);
        }
        List<Section> dashSections = scanByDelimiter(diff, "--- ", false);
        if (!dashSections.isEmpty()) {
            return new ParsedDiff(dashSections, false);
        }
        Section whole = new Section();
        forEachLine(diff, line -> whole.addLine(line, false));
        return new ParsedDiff(List.of(whole), false);
    }

    private List<Section> scanByDelimiter(String diff, String delimiterPrefix, boolean extractPaths) {
        List<Section> sections = new ArrayList<>();
        Section preamble = new Section();
        Section[] current = {null};

        forEachLine(diff, line -> {
            if (line.startsWith(delimiterPrefix)) {
                if (current[0] == null) {
                    Section first = new Section();
                    for (String preambleLine : preamble.lines()) {
                        first.addLine(preambleLine, false);
                    }
                    current[0] = first;
                } else {
                    sections.add(current[0]);
                    current[0] = new Section();
                }
                current[0].addLine(line, extractPaths);
            } else if (current[0] != null) {
                current[0].addLine(line, extractPaths);
            } else {
                preamble.addLine(line, false);
            }
        });
        if (current[0] != null) {
            sections.add(current[0]);
        }
        return sections;
    }

    private void forEachLine(String text, java.util.function.Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        } catch (IOException e) {
            // StringReader never actually throws IOException; keep the compiler happy without forcing
            // every caller to declare a checked exception for an unreachable case.
            throw new UncheckedIOException(e);
        }
    }

    private List<String> collectAllFilePaths(List<Section> sections) {
        Set<String> all = new LinkedHashSet<>();
        for (Section section : sections) {
            all.addAll(section.filePaths());
        }
        return List.copyOf(all);
    }

    /** One file-based section of the diff being parsed: header lines + hunk lines, plus extracted paths. */
    private static final class Section {
        private final List<String> lines = new ArrayList<>();
        private int firstHunkLineIndex = -1;
        private final List<String> filePaths = new ArrayList<>();
        private String cachedText;

        void addLine(String line, boolean extractPaths) {
            if (firstHunkLineIndex < 0 && line.startsWith("@@")) {
                firstHunkLineIndex = lines.size();
            }
            if (firstHunkLineIndex < 0 && extractPaths) {
                extractPathFromHeaderLine(line);
            }
            lines.add(line);
            cachedText = null;
        }

        List<String> lines() {
            return lines;
        }

        List<String> filePaths() {
            return filePaths;
        }

        String primaryPath() {
            return filePaths.isEmpty() ? "(unknown file)" : filePaths.get(0);
        }

        /** Full raw text of this section (header + hunks), one line per {@code \n}-terminated line. */
        String fullText() {
            if (cachedText == null) {
                StringBuilder sb = new StringBuilder();
                for (String line : lines) {
                    sb.append(line).append('\n');
                }
                cachedText = sb.toString();
            }
            return cachedText;
        }

        /** Everything from the start of the section through the line before the first hunk marker. */
        String headerText() {
            int end = firstHunkLineIndex < 0 ? lines.size() : firstHunkLineIndex;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < end; i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        }

        /** Each hunk's full text (its {@code @@...@@} line through the line before the next one, or end). */
        List<String> hunkTexts() {
            if (firstHunkLineIndex < 0) {
                return List.of();
            }
            List<String> hunks = new ArrayList<>();
            StringBuilder current = null;
            for (int i = firstHunkLineIndex; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("@@")) {
                    if (current != null) {
                        hunks.add(current.toString());
                    }
                    current = new StringBuilder();
                }
                current.append(line).append('\n');
            }
            if (current != null) {
                hunks.add(current.toString());
            }
            return hunks;
        }

        /**
         * CSR-11: only ever called while {@code firstHunkLineIndex < 0}, i.e. strictly within the
         * header region (before the first {@code @@} hunk marker) — never on a line that could be diff
         * *content* (a removed/added line whose text happens to start with {@code --- }/{@code +++ }).
         */
        private void extractPathFromHeaderLine(String line) {
            if (line.startsWith("diff --git ")) {
                String rest = line.substring("diff --git ".length());
                int bIdx = rest.lastIndexOf(" b/");
                if (bIdx >= 0) {
                    addPath(rest.substring(bIdx + 3));
                    return;
                }
            }
            if (line.startsWith("+++ ")) {
                String p = line.substring(4).trim();
                if (!p.equals("/dev/null")) {
                    addPath(p.startsWith("b/") ? p.substring(2) : p);
                }
                return;
            }
            if (line.startsWith("--- ") && filePaths.isEmpty()) {
                String p = line.substring(4).trim();
                if (!p.equals("/dev/null")) {
                    addPath(p.startsWith("a/") ? p.substring(2) : p);
                }
            }
        }

        private void addPath(String path) {
            if (path != null && !path.isBlank() && !filePaths.contains(path)) {
                filePaths.add(path);
            }
        }
    }
}
