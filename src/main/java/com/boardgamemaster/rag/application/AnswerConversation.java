package com.boardgamemaster.rag.application;

import java.util.*;

/** Context resolves references only; conversation and lesson text are never rule evidence. */
public record AnswerConversation(UUID sessionId, String game, String edition, String language, int playerCount,
        String teachingDepth, String stage, String lessonTitle, String lessonExplanation, List<Turn> recentMessages, SetupContext setup) {
    public record SetupContext(String stepKey, String title, String instruction) { }
    public AnswerConversation(UUID sessionId, String game, String edition, String language, int playerCount,
            String teachingDepth, String stage, String lessonTitle, String lessonExplanation, List<Turn> recentMessages) {
        this(sessionId, game, edition, language, playerCount, teachingDepth, stage, lessonTitle, lessonExplanation, recentMessages, null);
    }
    public record Turn(String role, String content) { }
    public AnswerConversation { recentMessages = List.copyOf(recentMessages); }
    public String retrievalQuery(String question) {
        return retrievalQuery(question, 4000);
    }
    public String retrievalQuery(String question, int limit) {
        // Preserve the question. Bound each cue so a long instruction cannot crowd out all other topics.
        String previous = recentMessages.reversed().stream().filter(m -> m.role().equals("USER")).map(Turn::content).findFirst().orElse("");
        String cues = (setup == null ? "" : "\nCurrent setup step: " + prefix(setup.title(), 160)
                + "\nSetup instruction: " + prefix(setup.instruction(), 800))
                + "\nPrevious question: " + prefix(previous, 400)
                + "\nCurrent lesson: " + prefix(lessonTitle, 160)
                + "\nLesson explanation: " + prefix(lessonExplanation, 800);
        int available = Math.max(0, limit - question.length());
        return question + prefix(cues, available);
    }
    private static String prefix(String value, int limit) {
        int end = Math.min(limit, value.length());
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) { end--; }
        return value.substring(0, end);
    }
}
