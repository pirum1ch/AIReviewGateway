package com.review.gateway.service;

import com.review.gateway.service.DiffPositionResolver.PathLine;
import com.review.gateway.service.DiffPositionResolver.ResolvedLine;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Plain unit tests (no Spring context) for {@link DiffPositionResolver} — DPR-02's totality guarantee
 * (~12 adversarial diffs, no exception, always a valid map) plus DPR-05's asymmetric path normalization
 * and basic added/context/removed/multi-hunk/multi-file/added-file/deleted-file resolution.
 */
class DiffPositionResolverTest {

    private final DiffPositionResolver resolver = new DiffPositionResolver();

    private Set<PathLine> wanted(PathLine... keys) {
        return Set.of(keys);
    }

    // ---- DPR-02: totality over degenerate/empty inputs ----

    @Test
    void nullDiffReturnsEmptyMap() {
        assertThat(resolver.resolve(null, wanted(new PathLine("A.java", 1)))).isEmpty();
    }

    @Test
    void emptyDiffReturnsEmptyMap() {
        assertThat(resolver.resolve("", wanted(new PathLine("A.java", 1)))).isEmpty();
    }

    @Test
    void nullWantedReturnsEmptyMap() {
        assertThatCode(() -> resolver.resolve("diff --git a/A.java b/A.java", null)).doesNotThrowAnyException();
        assertThat(resolver.resolve("diff --git a/A.java b/A.java", null)).isEmpty();
    }

    @Test
    void emptyWantedReturnsEmptyMapWithoutParsing() {
        assertThat(resolver.resolve("diff --git a/A.java b/A.java", Set.of())).isEmpty();
    }

    // ---- DPR-02: ~12 adversarial diffs -- no exception, always a valid (possibly empty) map ----

    @Test
    void malformedHunkHeaderWithHugeDigitCountIsSkippedNotThrown() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -99999999999999999999,1 +1,1 @@\n+line\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
        assertThat(resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).isEmpty();
    }

    @Test
    void bareAtAtHeaderIsSkippedNotThrown() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@\n+line\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
    }

    @Test
    void danglingMinusHeaderIsSkippedNotThrown() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -\n+line\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
    }

    @Test
    void headerWithNoDigitsAtAllIsSkippedNotThrown() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ - + @@\n+line\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
    }

    @Test
    void headerWithNoPlusAtAllIsSkippedNotThrown() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,3 @@\n+line\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
    }

    @Test
    void plusPlusPlusLineWithNoPathIsSkippedNotThrown() {
        String diff = "--- a/A.java\n+++ \n@@ -1,1 +1,1 @@\n+line\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
        assertThat(resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).isEmpty();
    }

    @Test
    void aHundredThousandLineHunkDoesNotThrowAndResolvesTheRequestedLine() {
        StringBuilder diff = new StringBuilder("--- a/Big.java\n+++ b/Big.java\n@@ -1,1 +1,100000 @@\n");
        IntStream.rangeClosed(1, 100_000).forEach(i -> diff.append("+line ").append(i).append('\n'));
        String text = diff.toString();

        assertThatCode(() -> resolver.resolve(text, wanted(new PathLine("Big.java", 100_000)))).doesNotThrowAnyException();
        Map<PathLine, ResolvedLine> result = resolver.resolve(text, wanted(new PathLine("Big.java", 100_000)));
        assertThat(result).containsKey(new PathLine("Big.java", 100_000));
    }

    @Test
    void loneSurrogatesDoNotThrow() {
        // A raw lone-surrogate code unit is computed here (never embedded as a literal source-file
        // character) -- keeping the .java source itself plain ASCII/valid UTF-8.
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n+bad" + (char) 0xD800 + "text\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
    }

    @Test
    void nulBytesDoNotThrowAndTheLineStillResolves() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n+bad" + (char) 0x0000 + "text\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
        assertThat(resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).containsKey(new PathLine("A.java", 1));
    }

    @Test
    void carriageReturnOnlyLineEndingsDoNotThrowAndStillResolve() {
        String diff = "--- a/A.java\r+++ b/A.java\r@@ -1,1 +1,1 @@\r+line\r";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
        assertThat(resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).containsKey(new PathLine("A.java", 1));
    }

    @Test
    void oneHugeSingleLineDiffDoesNotThrow() {
        String diff = "x".repeat(195_000);
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
        assertThat(resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).isEmpty();
    }

    @Test
    void contentLineWithUnexpectedMarkerEndsTheHunkDefensivelyWithoutThrowing() {
        // A line inside what looked like a hunk that doesn't start with +/-/space/backslash -- e.g. a
        // truncated/corrupted diff. Must not throw; the hunk just ends there.
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,2 +1,2 @@\n+kept\n#garbage-not-a-diff-marker\n";
        assertThatCode(() -> resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).doesNotThrowAnyException();
        assertThat(resolver.resolve(diff, wanted(new PathLine("A.java", 1)))).containsKey(new PathLine("A.java", 1));
    }

    // ---- basic resolution: added / context / removed / multi-hunk / multi-file ----

    @Test
    void addedLineResolvesWithNullOldLine() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,2 @@\n line1\n+line2\n";
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("A.java", 2)));

        ResolvedLine resolved = result.get(new PathLine("A.java", 2));
        assertThat(resolved).isNotNull();
        assertThat(resolved.newPath()).isEqualTo("A.java");
        assertThat(resolved.oldPath()).isEqualTo("A.java");
        assertThat(resolved.oldLine()).isNull();
        assertThat(resolved.newLine()).isEqualTo(2);
    }

    @Test
    void contextLineResolvesWithBothOldAndNewLine() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -5,2 +5,3 @@\n context\n+added\n more context\n";
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("A.java", 5)));

        ResolvedLine resolved = result.get(new PathLine("A.java", 5));
        assertThat(resolved).isNotNull();
        assertThat(resolved.oldLine()).isEqualTo(5);
        assertThat(resolved.newLine()).isEqualTo(5);
    }

    @Test
    void removedLineIsNeverResolvable() {
        // "new-file interpretation only" -- a removed line has no new-file counterpart.
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,2 +1,1 @@\n-removed line\n kept\n";
        // The removed line has no new-file line number to key on at all -- there is no PathLine that
        // could ever name it, which is exactly the point.
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("A.java", 1)));
        assertThat(result).containsKey(new PathLine("A.java", 1)); // the surviving context line, not the removed one
        assertThat(result.get(new PathLine("A.java", 1)).newLine()).isEqualTo(1);
    }

    @Test
    void multiHunkSameFileResolvesLinesFromBothHunks() {
        String diff = "--- a/A.java\n+++ b/A.java\n"
                + "@@ -1,1 +1,1 @@\n+first hunk line\n"
                + "@@ -50,1 +50,1 @@\n+second hunk line\n";
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff,
                wanted(new PathLine("A.java", 1), new PathLine("A.java", 50)));

        assertThat(result).containsKeys(new PathLine("A.java", 1), new PathLine("A.java", 50));
    }

    @Test
    void multiFileDiffResolvesLinesFromEachFileIndependently() {
        String diff = "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n+a-line\n"
                + "diff --git a/B.java b/B.java\n--- a/B.java\n+++ b/B.java\n@@ -1,1 +1,1 @@\n+b-line\n";
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff,
                wanted(new PathLine("A.java", 1), new PathLine("B.java", 1)));

        assertThat(result.get(new PathLine("A.java", 1)).newPath()).isEqualTo("A.java");
        assertThat(result.get(new PathLine("B.java", 1)).newPath()).isEqualTo("B.java");
    }

    @Test
    void addedFileUsesNewPathAsOldPathAndNeverTransmitsDevNull() {
        String diff = "diff --git a/New.java b/New.java\n--- /dev/null\n+++ b/New.java\n@@ -0,0 +1,1 @@\n+brand new\n";
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("New.java", 1)));

        ResolvedLine resolved = result.get(new PathLine("New.java", 1));
        assertThat(resolved).isNotNull();
        assertThat(resolved.oldPath()).isEqualTo("New.java"); // never "/dev/null" -- GitLab's own convention
        assertThat(resolved.newPath()).isEqualTo("New.java");
        assertThat(resolved.oldLine()).isNull();
    }

    @Test
    void deletedFileHasNoResolvableNewFileLines() {
        String diff = "diff --git a/Gone.java b/Gone.java\n--- a/Gone.java\n+++ /dev/null\n@@ -1,1 +0,0 @@\n-old content\n";
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("Gone.java", 1)));

        assertThat(result).isEmpty();
    }

    // ---- DPR-05: asymmetric path normalization + ambiguity rejection ----

    @Test
    void llmSuppliedPathIsNeverStrippedOfItsALeadingPrefix() {
        // The diff has a real file literally named "x.java" (git header "--- a/x.java" normalizes to
        // "x.java") and, separately, a real file literally named "a/x.java" (nested under "a/", git
        // header "--- a/a/x.java" normalizes -- ONE strip only -- to "a/x.java"). An LLM naming
        // "a/x.java" (unstripped, exact text) must resolve to its OWN file's line, never to x.java's.
        String diff = "diff --git a/x.java b/x.java\n--- a/x.java\n+++ b/x.java\n@@ -1,1 +1,1 @@\n+top-level x\n"
                + "diff --git a/a/x.java b/a/x.java\n--- a/a/x.java\n+++ b/a/x.java\n@@ -9,1 +9,1 @@\n+nested x\n";

        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("a/x.java", 9)));

        ResolvedLine resolved = result.get(new PathLine("a/x.java", 9));
        assertThat(resolved).isNotNull();
        assertThat(resolved.newPath()).isEqualTo("a/x.java"); // the nested file's own path, not "x.java"
    }

    @Test
    void llmKeyMatchingTheXJavaPathDoesNotAccidentallyMatchTheNestedFile() {
        String diff = "diff --git a/x.java b/x.java\n--- a/x.java\n+++ b/x.java\n@@ -1,1 +1,1 @@\n+top-level x\n"
                + "diff --git a/a/x.java b/a/x.java\n--- a/a/x.java\n+++ b/a/x.java\n@@ -9,1 +9,1 @@\n+nested x\n";

        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("x.java", 1)));

        ResolvedLine resolved = result.get(new PathLine("x.java", 1));
        assertThat(resolved).isNotNull();
        assertThat(resolved.newPath()).isEqualTo("x.java");
    }

    @Test
    void ambiguousDiffDerivedPathsAreDroppedFromTheIndexEntirely() {
        // Two distinct diff entries normalize to the identical key "x.java" -- one via the standard git
        // "a/" prefix, the other via a plain "./"-prefixed header (a legitimate, if unusual, unified-diff
        // shape). Genuinely ambiguous: neither should ever be resolvable via the collided key.
        String diff = "--- a/x.java\n+++ b/x.java\n@@ -1,1 +1,1 @@\n+from git-style header\n"
                + "--- ./x.java\n+++ ./x.java\n@@ -1,1 +1,1 @@\n+from dot-slash header\n";

        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("x.java", 1)));

        assertThat(result).doesNotContainKey(new PathLine("x.java", 1));
    }

    @Test
    void exactMatchOnlyNoSuffixOrBasenameFallback() {
        String diff = "--- a/src/main/A.java\n+++ b/src/main/A.java\n@@ -1,1 +1,1 @@\n+line\n";

        // LLM names only the basename, not the full path -- must not fall back to a suffix/basename match.
        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, wanted(new PathLine("A.java", 1)));

        assertThat(result).isEmpty();
    }

    @Test
    void resultMapIsTotalAndOnlyContainsRequestedKeysThatWereActuallyResolvable() {
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n+line\n";
        Set<PathLine> manyWanted = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new PathLine("A.java", i))
                .collect(Collectors.toUnmodifiableSet());

        Map<PathLine, ResolvedLine> result = resolver.resolve(diff, manyWanted);

        assertThat(result).hasSize(1);
        assertThat(result).containsOnlyKeys(new PathLine("A.java", 1));
    }
}
