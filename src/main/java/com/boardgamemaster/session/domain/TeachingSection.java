package com.boardgamemaster.session.domain;

import java.util.*;
import static com.boardgamemaster.session.domain.SessionException.Code.INVALID_TEACHING_FLOW;

public record TeachingSection(String key, int order, String title, String explanation,
        Set<TeachingDepth> depths, List<String> examples, List<String> tips, List<String> references, boolean setupTrigger) {
    public TeachingSection(String key, int order, String title, String explanation, Set<TeachingDepth> depths,
            List<String> examples, List<String> tips, List<String> references) {
        this(key, order, title, explanation, depths, examples, tips, references, false);
    }
    public TeachingSection {
        key = required(key, 80);
        if (!key.matches("[A-Z][A-Z0-9_]*") || order < 0) { throw invalid(); }
        title = required(title, 200); explanation = required(explanation, 8000);
        depths = depths == null ? Set.of(TeachingDepth.values()) : Set.copyOf(depths);
        if (depths.isEmpty()) { throw invalid(); }
        if (setupTrigger && !depths.containsAll(Set.of(TeachingDepth.values()))) { throw invalid(); }
        examples = bounded(examples); tips = bounded(tips); references = bounded(references);
    }
    private static String required(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) { throw invalid(); }
        return value.strip();
    }
    private static List<String> bounded(List<String> values) {
        if (values == null) { return List.of(); }
        if (values.size() > 10) { throw invalid(); }
        return values.stream().map(v -> required(v, 2000)).toList();
    }
    private static SessionException invalid() {
        return new SessionException(INVALID_TEACHING_FLOW, "Teaching section fields are invalid or exceed their limits");
    }
}
