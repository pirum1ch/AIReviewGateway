package com.review.gateway.service;

import org.springframework.stereotype.Service;

/**
 * Structured Review Output (architecture SRO-65, threat model SOR-01, BLOCKING): the constrained
 * alphabet a file path must satisfy, <b>after</b> {@link TextSanitizer#sanitizePath}, to be eligible as
 * a JSON Schema key / GBNF literal / Markdown inline-code-span content / structured-output published
 * comment file attribution.
 *
 * <p>Pure predicate, no state — a path failing {@link #isEligible} is rejected wholesale at
 * {@code POST /reviews} (SRO-16/17/65's {@code 422 STRUCTURED_OUTPUT_UNSUPPORTED}) for a structured
 * {@code promptVersion}; v1/v2 path handling is completely untouched — this class is consulted only on
 * the structured-output edge-validation path, never inside {@code TextSanitizer}/{@code
 * ChunkContextRenderer} themselves (CSR-09/CSR-10/F-DC-02 non-regression).
 *
 * <p>One predicate deliberately closes five findings at once (threat model §4.1/SOR-01): the {@code
 * {{}}}-stripping prompt/schema divergence (SOT-02), the Markdown inline-code-span breakout in the
 * comment header (SOT-07), git's {@code core.quotePath} octal-escaped/quoted mangling (SOT-15), the
 * {@code --jinja} schema-echo framing (SOT-22), and the third-party GBNF-escaping question (SOT-24).
 */
@Service
public class StructuredPathValidator {

    /**
     * {@code { } " \ ` [ ] | *} — closes the Markdown-header breakout (backtick/brackets/pipe/asterisk)
     * and the JSON/GBNF-structural characters (braces/quote/backslash) in one alphabet.
     */
    private static final String FORBIDDEN_CHARACTERS = "{}\"\\`[]|*";

    /**
     * @param sanitizedPath a path already put through {@link TextSanitizer#sanitizePath} — this method
     *                       does not itself strip anything, it only judges eligibility
     * @param maxPathChars   {@code gateway.structured.max-path-chars}
     * @return {@code true} iff {@code sanitizedPath} is non-null, non-empty, at most {@code
     *         maxPathChars} characters, contains none of {@value #FORBIDDEN_CHARACTERS} or any Unicode
     *         whitespace, has no leading {@code '/'}, and no {@code ".."} path segment
     */
    public boolean isEligible(String sanitizedPath, int maxPathChars) {
        if (sanitizedPath == null || sanitizedPath.isEmpty()) {
            return false;
        }
        if (sanitizedPath.length() > Math.max(0, maxPathChars)) {
            return false;
        }
        if (sanitizedPath.startsWith("/")) {
            return false;
        }
        for (String segment : sanitizedPath.split("/", -1)) {
            if (segment.equals("..")) {
                return false;
            }
        }
        int length = sanitizedPath.length();
        for (int i = 0; i < length; ) {
            int codePoint = sanitizedPath.codePointAt(i);
            if (FORBIDDEN_CHARACTERS.indexOf(codePoint) >= 0) {
                return false;
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }
}
