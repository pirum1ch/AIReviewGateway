package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.PromptSectionsMissingException;
import com.review.gateway.model.ReviewPromptSection;
import com.review.gateway.model.enums.PromptBundleMode;
import com.review.gateway.model.enums.PromptSectionKind;
import com.review.gateway.model.enums.PromptSectionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PromptMessageFormatter} (PMR-01/09/22/24, F-PM-07/F-PM-08).
 */
class PromptMessageFormatterTest {

    private PromptMessageFormatter newFormatter(GatewayProperties properties) {
        return new PromptMessageFormatter(properties, new PromptAssembler(properties, new DiffSizeValidator(properties)));
    }

    /** Wraps content in the same PMR-02 delimiter {@code PromptAssembler} applies at create time. */
    private String wrapped(GatewayProperties properties, PromptSectionKind kind, String content) {
        return new PromptAssembler(properties, new DiffSizeValidator(properties)).delimitedBlock(kind, content);
    }

    private ReviewPromptSection section(int ordinal, PromptSectionKind kind, PromptSectionStatus status, String content) {
        return new ReviewPromptSection(1L, ordinal, kind, status, content, "corp/repo", "path.md", "main", "sha1",
                "hash", 5);
    }

    /** PROJECT_* content is stored delimiter-wrapped at create time -- these fixtures match that shape. */
    private List<ReviewPromptSection> fourSections(GatewayProperties properties) {
        return List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "corporate base text"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "corporate rules text"),
                section(2, PromptSectionKind.PROJECT_ARCHITECTURE, PromptSectionStatus.PRESENT,
                        wrapped(properties, PromptSectionKind.PROJECT_ARCHITECTURE, "project architecture text")),
                section(3, PromptSectionKind.PROJECT_CODE_RULES, PromptSectionStatus.PRESENT,
                        wrapped(properties, PromptSectionKind.PROJECT_CODE_RULES, "project code rules text")));
    }

    // ---- PMR-24: mode=NONE is null, distinct from an empty list ----

    @Test
    void modeNoneAlwaysReturnsNullRegardlessOfSections() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);

        List<String> result = formatter.render(PromptBundleMode.NONE, fourSections(properties), null);

        assertThat(result).isNull();
    }

    @Test
    void modeNoneWithEmptySectionsListIsAlsoNull() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);

        assertThat(formatter.render(PromptBundleMode.NONE, List.of(), null)).isNull();
    }

    // ---- PMR-09: fail-closed when mandatory CORPORATE_* rows are missing ----

    @Test
    void repoModeWithMissingCorporateSectionsThrowsPromptSectionsMissingException() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);
        List<ReviewPromptSection> onlyProjectSections = List.of(
                section(0, PromptSectionKind.PROJECT_ARCHITECTURE, PromptSectionStatus.PRESENT, "arch"));

        assertThatThrownBy(() -> formatter.render(PromptBundleMode.REPO, onlyProjectSections, null))
                .isInstanceOf(PromptSectionsMissingException.class);
    }

    @Test
    void repoModeWithEmptySectionsListThrows() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);

        assertThatThrownBy(() -> formatter.render(PromptBundleMode.REPO, List.of(), null))
                .isInstanceOf(PromptSectionsMissingException.class);
    }

    @Test
    void repoModeWithBothCorporateSectionsPresentNeverThrows() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);
        List<ReviewPromptSection> corporateOnly = List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "base"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "rules"));

        assertThat(formatter.render(PromptBundleMode.REPO, corporateOnly, null)).isNotNull();
    }

    // ---- PMR-22: MULTI gives N ChatMessage-equivalent strings, SINGLE gives one ----

    @Test
    void multiFormatGivesOneMessagePerPresentSectionPlusPreambleTrailer() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setMessageFormat("MULTI");
        PromptMessageFormatter formatter = newFormatter(properties);

        List<String> messages = formatter.render(PromptBundleMode.REPO, fourSections(properties), null);

        // 2 corporate + preamble + 2 project + trailer = 6
        assertThat(messages).hasSize(6);
        assertThat(messages.get(0)).isEqualTo("corporate base text");
        assertThat(messages.get(1)).isEqualTo("corporate rules text");
        assertThat(messages.get(2)).isEqualTo(PromptAssembler.PREAMBLE);
        assertThat(messages.get(3)).isEqualTo(
                wrapped(properties, PromptSectionKind.PROJECT_ARCHITECTURE, "project architecture text"));
        assertThat(messages.get(4)).isEqualTo(
                wrapped(properties, PromptSectionKind.PROJECT_CODE_RULES, "project code rules text"));
        assertThat(messages.get(5)).isEqualTo(PromptAssembler.TRAILER);
    }

    @Test
    void multiFormatWithNoProjectSectionsOmitsPreambleAndTrailer() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setMessageFormat("MULTI");
        PromptMessageFormatter formatter = newFormatter(properties);
        List<ReviewPromptSection> corporateOnly = List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "base"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "rules"));

        List<String> messages = formatter.render(PromptBundleMode.REPO, corporateOnly, null);

        assertThat(messages).containsExactly("base", "rules");
    }

    @Test
    void singleFormatGivesExactlyOneJoinedMessage() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setMessageFormat("SINGLE");
        properties.getPrompt().setSectionSeparator("\n---\n");
        PromptMessageFormatter formatter = newFormatter(properties);

        List<String> messages = formatter.render(PromptBundleMode.REPO, fourSections(properties), null);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("corporate base text");
        assertThat(messages.get(0)).contains("corporate rules text");
        assertThat(messages.get(0)).contains(PromptAssembler.PREAMBLE);
        assertThat(messages.get(0)).contains("project architecture text");
        assertThat(messages.get(0)).contains(PromptAssembler.TRAILER);
        assertThat(messages.get(0)).contains("\n---\n");
    }

    // ---- PMR-22: backend override takes priority over global default; bad/null value never throws ----

    @Test
    void backendMessageFormatOverridesGlobalDefault() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setMessageFormat("MULTI");
        PromptMessageFormatter formatter = newFormatter(properties);
        List<ReviewPromptSection> corporateOnly = List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "base"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "rules"));

        List<String> messages = formatter.render(PromptBundleMode.REPO, corporateOnly, "SINGLE");

        assertThat(messages).hasSize(1);
    }

    @Test
    void invalidBackendMessageFormatFallsBackToGlobalDefaultWithoutThrowing() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setMessageFormat("MULTI");
        PromptMessageFormatter formatter = newFormatter(properties);
        List<ReviewPromptSection> corporateOnly = List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "base"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "rules"));

        List<String> messages = formatter.render(PromptBundleMode.REPO, corporateOnly, "GARBAGE-VALUE");

        assertThat(messages).containsExactly("base", "rules"); // MULTI (global default), not a throw
    }

    @Test
    void nullBackendMessageFormatFallsBackToGlobalDefaultWithoutThrowing() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setMessageFormat("SINGLE");
        PromptMessageFormatter formatter = newFormatter(properties);
        List<ReviewPromptSection> corporateOnly = List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "base"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "rules"));

        List<String> messages = formatter.render(PromptBundleMode.REPO, corporateOnly, null);

        assertThat(messages).hasSize(1); // SINGLE (global default)
    }

    // ---- ABSENT sections never contribute a message ----

    @Test
    void absentProjectSectionsAreSkippedNotRenderedAsEmptyMessages() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);
        List<ReviewPromptSection> sections = List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "base"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "rules"),
                section(2, PromptSectionKind.PROJECT_ARCHITECTURE, PromptSectionStatus.ABSENT, ""),
                section(3, PromptSectionKind.PROJECT_CODE_RULES, PromptSectionStatus.ABSENT, ""));

        List<String> messages = formatter.render(PromptBundleMode.REPO, sections, null);

        assertThat(messages).containsExactly("base", "rules");
    }

    // ---- F-PM-07: claim-time max-sections cap ----

    @Test
    void rowCountAtMaxSectionsIsRenderedInFull() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().getLimits().setMaxSections(4);
        PromptMessageFormatter formatter = newFormatter(properties);

        List<String> messages = formatter.render(PromptBundleMode.REPO, fourSections(properties), null);

        assertThat(messages).hasSize(6); // 2 corporate + preamble + 2 project + trailer, nothing dropped
    }

    @Test
    void rowCountOverMaxSectionsDropsTheExcessRatherThanRenderingThem() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().getLimits().setMaxSections(2); // below the 4 rows supplied
        PromptMessageFormatter formatter = newFormatter(properties);

        // ordinal-ascending order (as QueueManager supplies it) keeps CORPORATE_* first -- with the cap
        // at 2, only the two mandatory corporate rows survive; the two PROJECT_* rows are dropped.
        List<String> messages = formatter.render(PromptBundleMode.REPO, fourSections(properties), null);

        assertThat(messages).containsExactly("corporate base text", "corporate rules text");
    }

    @Test
    void rowCountOverMaxSectionsThatDropsBelowMandatoryCorporateStillFailsClosed() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().getLimits().setMaxSections(1); // drops CORPORATE_REVIEW_RULES too
        PromptMessageFormatter formatter = newFormatter(properties);

        assertThatThrownBy(() -> formatter.render(PromptBundleMode.REPO, fourSections(properties), null))
                .isInstanceOf(PromptSectionsMissingException.class);
    }

    // ---- F-PM-08: claim-time re-derivation of the PMR-02 delimiter wrapping ----

    @Test
    void projectSectionMissingItsDelimiterWrapperIsRewrappedDefensivelyAtClaimTime() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);
        // Simulates a corrupted/hand-edited/partially-backfilled row: PRESENT but never wrapped.
        List<ReviewPromptSection> sections = List.of(
                section(0, PromptSectionKind.CORPORATE_BASE, PromptSectionStatus.PRESENT, "base"),
                section(1, PromptSectionKind.CORPORATE_REVIEW_RULES, PromptSectionStatus.PRESENT, "rules"),
                section(2, PromptSectionKind.PROJECT_ARCHITECTURE, PromptSectionStatus.PRESENT, "unwrapped architecture text"));

        List<String> messages = formatter.render(PromptBundleMode.REPO, sections, null);

        String expected = wrapped(properties, PromptSectionKind.PROJECT_ARCHITECTURE, "unwrapped architecture text");
        assertThat(messages).contains(expected);
        assertThat(messages).doesNotContain("unwrapped architecture text"); // never emitted bare/undelimited
    }

    @Test
    void projectSectionAlreadyWrappedIsNotDoubleWrapped() {
        GatewayProperties properties = new GatewayProperties();
        PromptMessageFormatter formatter = newFormatter(properties);

        List<String> messages = formatter.render(PromptBundleMode.REPO, fourSections(properties), null);

        String expected = wrapped(properties, PromptSectionKind.PROJECT_ARCHITECTURE, "project architecture text");
        assertThat(messages).contains(expected);
        // Not wrapped a second time: the expected (single-wrap) form must not itself be a strict
        // substring of a longer, double-wrapped rendering.
        long occurrences = messages.stream().filter(expected::equals).count();
        assertThat(occurrences).isEqualTo(1);
    }
}
