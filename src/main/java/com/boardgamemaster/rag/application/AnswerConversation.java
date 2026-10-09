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
        // Preserve the current question and prioritize the user's prior topic over authored guidance.
        String previous = recentMessages.reversed().stream().filter(m -> m.role().equals("USER")).map(Turn::content).findFirst().orElse("");
        // A short follow-up needs topic cues, not an embedding dominated by a long setup procedure.
        // This is a language-independent length heuristic, not semantic reference resolution.
        int guidanceLimit = !previous.isBlank() && question.codePointCount(0, question.length()) <= 80 ? 120 : 800;
        String cues = "\nPrevious question: " + prefix(previous, 400)
                + (setup == null ? "" : "\nCurrent setup step: " + prefix(setup.title(), 160))
                + "\nCurrent lesson: " + prefix(lessonTitle, 160)
                + (setup == null ? "" : "\nSetup instruction: " + prefix(setup.instruction(), guidanceLimit))
                + "\nLesson explanation: " + prefix(lessonExplanation, guidanceLimit);
        int available = Math.max(0, limit - question.length());
        return question + prefix(cues, available);
    }
    private static String prefix(String value, int limit) {
        int end = Math.min(limit, value.length());
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) { end--; }
        return value.substring(0, end);
    }
}
