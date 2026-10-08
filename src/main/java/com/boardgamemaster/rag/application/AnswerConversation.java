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
        // Preserve the complete current question; remaining space supplies recent topic and lesson cues.
        String previous = recentMessages.reversed().stream().filter(m -> m.role().equals("USER")).map(Turn::content).findFirst().orElse("");
        String cues = (setup == null ? "" : "\nCurrent setup step: " + setup.title()) + "\nPrevious question: " + previous + "\nCurrent lesson: " + lessonTitle;
        int available = Math.max(0, limit - question.length());
        int end = Math.min(available, cues.length());
        if (end > 0 && end < cues.length() && Character.isHighSurrogate(cues.charAt(end - 1))) { end--; }
        return question + cues.substring(0, end);
    }
}
