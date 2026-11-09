package com.boardgamemaster.rag.application;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Allows only whitespace-run differences, typographic variants of the same punctuation (straight
 * versus curly quotes and apostrophes, hyphen versus en or em dash) and Markdown emphasis markers
 * around words, which rulebooks and models write differently. Returns a contiguous span of the
 * original source, so the shown quote is always verbatim.
 */
final class SourceQuoteMatcher {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MAX_QUOTE_CHARACTERS = 1000;
    private static final String APOSTROPHES = "'‘’ʼ";
    private static final String DOUBLE_QUOTES = "\"“”";
    private static final String DASHES = "-‐‑–—";
    private static final String EMPHASIS = "(?:[*_]{1,3})?";

    private SourceQuoteMatcher() { }

    static Optional<String> find(String content, String quote) {
        if (quote == null || quote.isBlank() || quote.length() > MAX_QUOTE_CHARACTERS) { return Optional.empty(); }
        String normalized = SPACE.matcher(quote).replaceAll(" ").strip();
        if (normalized.isEmpty()) { return Optional.empty(); }
        if (content.contains(quote)) { return Optional.of(quote); }
        // Every other non-whitespace character is literal. A whitespace run must remain a whitespace run:
        // this cannot merge words, remove punctuation, expand ellipses or change numbers. Markdown emphasis
        // markers (*, **, _, __) may wrap a source word, because models quote bold rule text without them.
        String expression = String.join("(?U:\\s+)", java.util.Arrays.stream(normalized.split(" "))
                .map(word -> EMPHASIS + literal(word) + EMPHASIS).toList());
        var matched = Pattern.compile(expression).matcher(content);
        while (matched.find()) {
            if (matched.end() - matched.start() <= MAX_QUOTE_CHARACTERS) {
                return Optional.of(content.substring(matched.start(), matched.end()));
            }
        }
        return Optional.empty();
    }

    /** One word of the quote as a regex: literal, except that typographic punctuation variants match each other. */
    private static String literal(String word) {
        var regex = new StringBuilder();
        word.codePoints().forEach(c -> {
            String text = new String(Character.toChars(c));
            if (APOSTROPHES.contains(text)) { regex.append("[").append(Pattern.quote(APOSTROPHES)).append("]"); }
            else if (DOUBLE_QUOTES.contains(text)) { regex.append("[").append(Pattern.quote(DOUBLE_QUOTES)).append("]"); }
            else if (DASHES.contains(text)) { regex.append("[").append(Pattern.quote(DASHES)).append("]"); }
            else { regex.append(Pattern.quote(text)); }
        });
        return regex.toString();
    }
}
