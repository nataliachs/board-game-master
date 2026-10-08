package com.boardgamemaster.rag.application;

import java.util.Optional;
import java.util.regex.Pattern;

/** Allows only whitespace-run differences; returns a contiguous span of the original source. */
final class SourceQuoteMatcher {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MAX_QUOTE_CHARACTERS = 1000;

    private SourceQuoteMatcher() { }

    static Optional<String> find(String content, String quote) {
        if (quote == null || quote.isBlank() || quote.length() > MAX_QUOTE_CHARACTERS) { return Optional.empty(); }
        String normalized = SPACE.matcher(quote).replaceAll(" ").strip();
        if (normalized.isEmpty()) { return Optional.empty(); }
        if (content.contains(quote)) { return Optional.of(quote); }
        // Every non-whitespace character is literal. A whitespace run must remain a whitespace run:
        // this cannot merge words, remove punctuation, expand ellipses or change numbers.
        String expression = String.join("(?U:\\s+)", java.util.Arrays.stream(normalized.split(" "))
                .map(Pattern::quote).toList());
        var matched = Pattern.compile(expression).matcher(content);
        while (matched.find()) {
            if (matched.end() - matched.start() <= MAX_QUOTE_CHARACTERS) {
                return Optional.of(content.substring(matched.start(), matched.end()));
            }
        }
        return Optional.empty();
    }
}
