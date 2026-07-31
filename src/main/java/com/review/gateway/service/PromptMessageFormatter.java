package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.PromptSectionsMissingException;
import com.review.gateway.model.ReviewPromptSection;
import com.review.gateway.model.enums.PromptBundleMode;
import com.review.gateway.model.enums.PromptMessageFormat;
import com.review.gateway.model.enums.PromptSectionKind;
import com.review.gateway.model.enums.PromptSectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Claim-time (architecture §8): {@code review_prompt_sections} DB rows → {@code
 * JobPayload.systemMessages}. Renders the same compile-time-constant preamble/trailer
 * ({@link PromptAssembler#PREAMBLE}/{@link PromptAssembler#TRAILER}) around the {@code PROJECT_*}
 * sections that were counted into the token budget at create time (PMR-01) — never persisted, never
 * fetched, always re-emitted identically.
 */
@Service
public class PromptMessageFormatter {

    private static final Logger log = LoggerFactory.getLogger(PromptMessageFormatter.class);

    private final GatewayProperties properties;
    private final PromptAssembler assembler;

    public PromptMessageFormatter(GatewayProperties properties, PromptAssembler assembler) {
        this.properties = properties;
        this.assembler = assembler;
    }

    /**
     * @return {@code null} for {@link PromptBundleMode#NONE} (PMR-09/24: today's legacy behavior — the
     *         Worker falls back to its own template {@code system:} block); never an empty list for that
     *         case. For {@link PromptBundleMode#REPO}, always at least the two mandatory corporate
     *         sections.
     * @throws PromptSectionsMissingException if {@code mode == REPO} but either mandatory
     *         {@code CORPORATE_*} row is missing from {@code sections} (PMR-09) — the caller must fail
     *         the job, never dispatch it with an empty/partial system prompt.
     */
    public List<String> render(PromptBundleMode mode, List<ReviewPromptSection> sections, String backendMessageFormatRaw) {
        if (mode == PromptBundleMode.NONE) {
            return null;
        }

        // F-PM-07: PMR-08's claim-time cardinality bound, re-checked here rather than trusted solely from
        // the schema (UNIQUE(review_id, kind) + the 4-value kind CHECK already make this unreachable via
        // the normal create path, but a hand-edited/backfilled row set is not this class's problem to
        // trust). Excess rows are dropped, not rendered -- ordinal-ascending order means CORPORATE_* is
        // always kept; a corrupted row set fails closed via the mandatory-section check below instead of
        // silently growing the rendered prompt.
        int maxSections = properties.getPrompt().getLimits().getMaxSections();
        List<ReviewPromptSection> boundedSections = sections;
        if (sections.size() > maxSections) {
            log.warn("review_prompt_sections row count ({}) exceeds gateway.prompt.limits.max-sections ({}); "
                    + "dropping the excess rather than rendering them", sections.size(), maxSections);
            boundedSections = sections.subList(0, maxSections);
        }

        Map<PromptSectionKind, ReviewPromptSection> byKind = new EnumMap<>(PromptSectionKind.class);
        for (ReviewPromptSection section : boundedSections) {
            byKind.put(section.getKind(), section);
        }

        ReviewPromptSection corporateBase = byKind.get(PromptSectionKind.CORPORATE_BASE);
        ReviewPromptSection corporateRules = byKind.get(PromptSectionKind.CORPORATE_REVIEW_RULES);
        if (corporateBase == null || corporateRules == null) {
            throw new PromptSectionsMissingException(
                    "prompt_bundle_mode=REPO but mandatory CORPORATE_* review_prompt_sections rows are missing");
        }

        ReviewPromptSection projectArchitecture = byKind.get(PromptSectionKind.PROJECT_ARCHITECTURE);
        ReviewPromptSection projectCodeRules = byKind.get(PromptSectionKind.PROJECT_CODE_RULES);
        boolean hasProjectContent = isPresent(projectArchitecture) || isPresent(projectCodeRules);

        List<String> pieces = new java.util.ArrayList<>();
        pieces.add(corporateBase.getContent());
        pieces.add(corporateRules.getContent());
        if (hasProjectContent) {
            pieces.add(PromptAssembler.PREAMBLE);
        }
        if (isPresent(projectArchitecture)) {
            pieces.add(wrappedProjectContent(projectArchitecture));
        }
        if (isPresent(projectCodeRules)) {
            pieces.add(wrappedProjectContent(projectCodeRules));
        }
        if (hasProjectContent) {
            pieces.add(PromptAssembler.TRAILER);
        }

        PromptMessageFormat format = resolveFormat(backendMessageFormatRaw);
        if (format == PromptMessageFormat.SINGLE) {
            String separator = properties.getPrompt().getSectionSeparator();
            return List.of(String.join(separator, pieces));
        }
        return List.copyOf(pieces);
    }

    /**
     * F-PM-08: the PMR-02 delimiter wrapping is applied once, at create time
     * ({@code PromptAssembler.toSection}), and trusted as stored thereafter -- this re-derives it from
     * {@code kind} at claim time instead of trusting that whatever wrote the row applied the wrapper. A
     * {@code PROJECT_*} row whose stored content is missing its begin/end delimiter lines (a future bug,
     * a partial backfill, a manual insert) is re-wrapped here rather than reaching the model's system
     * role undelimited — the same "re-check the trust-boundary property on every claim rather than
     * inherit it from create time" argument PMR-09's fail-closed check already makes for the mandatory
     * sections next to this one.
     */
    private String wrappedProjectContent(ReviewPromptSection section) {
        String content = section.getContent();
        if (assembler.isDelimited(section.getKind(), content)) {
            return content;
        }
        log.warn("PROJECT_* review_prompt_sections row (kind={}) is missing its expected delimiter wrapper "
                + "at claim time; re-wrapping defensively", section.getKind());
        return assembler.delimitedBlock(section.getKind(), content);
    }

    private boolean isPresent(ReviewPromptSection section) {
        return section != null && section.getStatus() == PromptSectionStatus.PRESENT;
    }

    /**
     * PMR-22: never {@link PromptMessageFormat#valueOf}s the raw DB text directly — an unrecognized/
     * {@code NULL} value degrades to the configured global default with a {@code WARN}, never throws.
     */
    private PromptMessageFormat resolveFormat(String backendMessageFormatRaw) {
        if (backendMessageFormatRaw != null && !backendMessageFormatRaw.isBlank()) {
            var parsed = PromptMessageFormat.fromNullable(backendMessageFormatRaw);
            if (parsed.isPresent()) {
                return parsed.get();
            }
            log.warn("Unrecognized backends.prompt_message_format (length={}) -- falling back to the "
                    + "configured global default", backendMessageFormatRaw.length());
        }
        return PromptMessageFormat.fromNullable(properties.getPrompt().getMessageFormat())
                .orElse(PromptMessageFormat.MULTI);
    }
}
