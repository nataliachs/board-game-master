package com.boardgamemaster.session.domain;

import java.time.Instant;
import java.util.*;
import static com.boardgamemaster.session.domain.SessionException.Code.INVALID_SESSION_ACTION;

public record GameSession(UUID id, UUID gameId, UUID gameVersionId, UUID teachingFlowId, int playerCount,
        TeachingDepth teachingDepth, SessionStage stage, Status status, String currentSectionKey,
        Set<String> completedSections, long revision, Instant createdAt, Instant endedAt) {
    public enum Status { ACTIVE, COMPLETED }
    public enum Action { CONTINUE, PREVIOUS, REPEAT, EXPLAIN_AGAIN }
    public GameSession { completedSections = Set.copyOf(completedSections); }

    public void requireActive() {
        if (status != Status.ACTIVE) { throw invalid("This session is completed"); }
    }
    public GameSession navigate(TeachingFlow flow, Action action) {
        requireActive();
        if (stage != SessionStage.TEACHING) { throw invalid("Lesson navigation requires the TEACHING stage"); }
        if (action == null || !flow.id().equals(teachingFlowId) || !flow.gameVersionId().equals(gameVersionId)) {
            throw invalid("Lesson navigation requires this session's pinned flow");
        }
        var sections = flow.sectionsFor(teachingDepth);
        int index = -1;
        for (int i = 0; i < sections.size(); i++) { if (sections.get(i).key().equals(currentSectionKey)) { index = i; break; } }
        if (index < 0) { throw invalid("Current lesson is not applicable to this session"); }
        var completed = new HashSet<>(completedSections);
        var nextStage = stage;
        if (action == Action.CONTINUE) {
            completed.add(currentSectionKey);
            if (index + 1 < sections.size()) { index++; } else { nextStage = SessionStage.PLAYING; }
        } else if (action == Action.PREVIOUS && index > 0) { index--; }
        return new GameSession(id, gameId, gameVersionId, teachingFlowId, playerCount, teachingDepth, nextStage,
                status, sections.get(index).key(), completed, revision + 1, createdAt, endedAt);
    }
    public GameSession complete(Instant now) {
        requireActive();
        return new GameSession(id, gameId, gameVersionId, teachingFlowId, playerCount, teachingDepth,
                SessionStage.COMPLETED, Status.COMPLETED, currentSectionKey, completedSections, revision + 1, createdAt, now);
    }
    private static SessionException invalid(String message) { return new SessionException(INVALID_SESSION_ACTION, message); }
}
