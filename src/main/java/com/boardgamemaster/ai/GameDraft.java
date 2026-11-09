package com.boardgamemaster.ai;

import java.util.List;

/**
 * A provider's first draft of a game's catalog details, setup steps and lessons, written from a
 * rulebook. It is only a draft: the admin reviews it before players see the game, and the
 * application applies its own validation when it is saved.
 */
public record GameDraft(String name, String publisher, String category, int minPlayers, int maxPlayers,
        int playTimeMinutes, String edition, String language, String description, List<Step> setupSteps, List<Lesson> lessons) {
    public record Step(String title, String instruction, List<String> notes, List<String> warnings) {
        public Step {
            title = text(title); instruction = text(instruction); notes = lines(notes); warnings = lines(warnings);
        }
    }
    public record Lesson(String title, String explanation, boolean quick, boolean standard, boolean detailed,
            boolean startsSetup, List<String> examples, List<String> tips, List<String> references) {
        public Lesson {
            title = text(title); explanation = text(explanation);
            examples = lines(examples); tips = lines(tips); references = lines(references);
        }
    }
    public GameDraft {
        name = text(name); publisher = text(publisher); category = text(category);
        edition = text(edition); language = text(language); description = text(description);
        setupSteps = setupSteps == null ? List.of() : setupSteps.stream().filter(s -> s != null).toList();
        lessons = lessons == null ? List.of() : lessons.stream().filter(l -> l != null).toList();
    }
    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static List<String> lines(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
    }
    @Override public String toString() { return "GameDraft[name=" + name + ", steps=" + setupSteps.size() + ", lessons=" + lessons.size() + "]"; }
}
