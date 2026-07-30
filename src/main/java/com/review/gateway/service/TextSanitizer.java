package com.review.gateway.service;

import org.springframework.stereotype.Component;

/**
 * Shared Cc/Cf/Zl/Zp + delimiter-character-class stripper (architecture §4, PMR-03), generalized out
 * of {@link ChunkContextRenderer#sanitizePath} (CSR-09/F-DC-02) so this feature's prompt-section
 * sanitization and the existing path sanitization share one implementation instead of copy-pasting the
 * F-DC-02 fixpoint/character-class lesson a second time.
 *
 * <p>Two entry points, both stripping Unicode Cc (control) and Cf (format — includes the bidi-override
 * Trojan-Source characters U+202A-U+202E, U+2066-U+2069) <b>before</b> anything delimiter-specific
 * (CSR-09 ordering, preserved):
 * <ul>
 *   <li>{@link #sanitizePath}: also strips Zl/Zp and the {@code '<'}/{@code '>'} character class,
 *       strips Cc <em>including</em> {@code \n}/{@code \t} (a file path has no legitimate newline), and
 *       caps length — byte-for-byte what {@code ChunkContextRenderer} always did.</li>
 *   <li>{@link #sanitizeSectionText}: keeps {@code \n}/{@code \t} (prose needs newlines), additionally
 *       strips Zl/Zp and every U+241E (the Prompt Manager section delimiter's character, {@code
 *       PromptAssembler}) code point, and does not cap length itself (the caller applies the
 *       token-budget cap).</li>
 * </ul>
 *
 * <p><b>F-DC-02 lesson, applied here too:</b> character-class stripping (removing every code point in
 * one pass) is what makes the stripped alphabet structurally unreconstructable — unlike
 * {@code String.replace(multiCharToken, "")}, which is single-pass and provably defeated by a
 * self-nesting payload ({@code X.substring(0,mid) + X + X.substring(mid)} collapses back into {@code
 * X}). Both methods here strip individual code points, never a multi-character token, so there is
 * nothing left in the output that could ever recombine via concatenation into a delimiter.
 */
@Component
public class TextSanitizer {

    /** The Prompt Manager delimiter's code point (architecture §4) — stripped from section prose. */
    public static final int DELIMITER_CODE_POINT = 0x241E;

    private static final String TRUNCATION_SUFFIX = "...";

    /**
     * CSR-09: strips Cc (all of it, including {@code \n}/{@code \t} — a path has no legitimate one),
     * Cf, Zl, Zp, and the {@code '<'}/{@code '>'} character class, then caps to {@code maxLength}.
     * Returns {@code null} if nothing publishable remains (e.g. a path made entirely of stripped
     * characters) — callers must treat {@code null} as "drop this value", never render/persist an empty
     * string in its place.
     */
    public String sanitizePath(String rawPath, int maxLength) {
        if (rawPath == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(rawPath.length());
        rawPath.codePoints().forEach(cp -> {
            if (!isControlOrFormatOrSeparator(cp) && cp != '<' && cp != '>') {
                sb.appendCodePoint(cp);
            }
        });
        String stripped = sb.toString().trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return capLength(stripped, maxLength);
    }

    /**
     * PMR-03: strips Cc <em>except</em> {@code \n}/{@code \t} (prose needs newlines — this is the one
     * difference from {@link #sanitizePath}), all Cf (bidi overrides), Zl, Zp, and every {@link
     * #DELIMITER_CODE_POINT} code point. Does not reject/cap on its own; the caller (fetch layer)
     * separately rejects NUL and enforces the byte/token cap.
     */
    public String sanitizeSectionText(String rawText) {
        if (rawText == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(rawText.length());
        rawText.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t') {
                sb.appendCodePoint(cp);
                return;
            }
            if (!isControlOrFormatOrSeparator(cp) && cp != DELIMITER_CODE_POINT) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    private boolean isControlOrFormatOrSeparator(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private String capLength(String text, int maxLength) {
        int max = Math.max(0, maxLength);
        if (text.length() <= max) {
            return text;
        }
        int cut = Math.max(0, max - TRUNCATION_SUFFIX.length());
        return text.substring(0, cut) + TRUNCATION_SUFFIX;
    }
}
