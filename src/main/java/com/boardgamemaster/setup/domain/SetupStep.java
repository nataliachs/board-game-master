package com.boardgamemaster.setup.domain;

import java.util.*;
import static com.boardgamemaster.setup.domain.SetupException.Code.INVALID_SETUP_FLOW;

public record SetupStep(String key, int order, String title, String instruction, List<String> notes, List<String> warnings) {
    public SetupStep {
        key = required(key, 80); title = required(title, 200); instruction = required(instruction, 8000);
        if (!key.matches("[A-Z][A-Z0-9_]*") || order < 0) { throw invalid(); }
        notes = bounded(notes); warnings = bounded(warnings);
    }
    private static String required(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit) { throw invalid(); }
        return text.strip();
    }
    private static List<String> bounded(List<String> values) {
        if (values == null) { return List.of(); }
        if (values.size() > 10) { throw invalid(); }
        return values.stream().map(v -> required(v, 2000)).toList();
    }
    private static SetupException invalid() { return new SetupException(INVALID_SETUP_FLOW, "Setup step fields are invalid or exceed their limits"); }
}
