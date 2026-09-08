package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WHR-21 (the blocking acceptance test): every fixture's assembled output is fed back through {@link
 * DiffChunker}'s actual parser, not eyeballed. {@code pathsTrusted() == true} and an exact file-path-set
 * match are the assertions that matter — not merely that the string "looks like a diff".
 */
class DiffAssemblerTest {

    private final DiffAssembler assembler = new DiffAssembler();
    private final GatewayProperties properties = new GatewayProperties();
    private final DiffChunker diffChunker =
            new DiffChunker(properties, new DiffSizeValidator(properties), new ChunkContextRenderer(properties, new TextSanitizer()));

    @Test
    void modifiedFileRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("a.txt", "a.txt", "100644", "100644", false, false, false,
                "@@ -1 +1 @@\n-old\n+new\n")));
    }

    @Test
    void newFileRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("new.txt", "new.txt", null, "100644", true, false, false,
                "@@ -0,0 +1 @@\n+content\n")));
    }

    @Test
    void deletedFileRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("old.txt", "old.txt", "100644", null, false, true, false,
                "@@ -1 +0,0 @@\n-content\n")));
    }

    @Test
    void renamedFileWithContentChangeRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("old.txt", "renamed.txt", "100644", "100644", false, false, true,
                "@@ -1 +1 @@\n-old\n+new\n")));
    }

    @Test
    void binaryFileMarkerRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("img.png", "img.png", "100644", "100644", false, false, false,
                "Binary files a/img.png and b/img.png differ\n")));
    }

    @Test
    void modeOnlyChangeRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("script.sh", "script.sh", "100644", "100755", false, false, false, "")));
    }

    @Test
    void multiHunkFileRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("multi.txt", "multi.txt", "100644", "100644", false, false, false,
                "@@ -1 +1 @@\n-a\n+b\n@@ -10 +10 @@\n-c\n+d\n")));
    }

    @Test
    void noTrailingNewlineFileRoundTripsThroughDiffChunker() {
        assertRoundTrips(List.of(entry("nonl.txt", "nonl.txt", "100644", "100644", false, false, false,
                "@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file")));
    }

    @Test
    void multipleFilesTogetherProduceTheExactCoverageSet() {
        assertRoundTrips(List.of(
                entry("a.txt", "a.txt", "100644", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"),
                entry("new.txt", "new.txt", null, "100644", true, false, false, "@@ -0,0 +1 @@\n+z\n"),
                entry("old.txt", "old.txt", "100644", null, false, true, false, "@@ -1 +0,0 @@\n-z\n")));
    }

    /** WHR-21's core assertion: {@code diffChunker.split(...)} must trust the paths and recover exactly this set. */
    private void assertRoundTrips(List<GitLabClient.DiffEntry> files) {
        String assembled = assembler.assemble(files);

        DiffChunker.ChunkPlan plan = diffChunker.split(assembled);

        assertThat(plan.pathsTrusted()).isTrue();
        Set<String> expectedPaths = files.stream().map(GitLabClient.DiffEntry::newPath).collect(Collectors.toSet());
        Set<String> actualPaths = plan.chunks().stream()
                .flatMap(chunk -> chunk.filePaths().stream())
                .collect(Collectors.toSet());
        assertThat(actualPaths).isEqualTo(expectedPaths);
    }

    private GitLabClient.DiffEntry entry(String oldPath, String newPath, String aMode, String bMode,
                                          boolean newFile, boolean deletedFile, boolean renamedFile, String diff) {
        return new GitLabClient.DiffEntry(oldPath, newPath, aMode, bMode, newFile, deletedFile, renamedFile, diff);
    }
}
