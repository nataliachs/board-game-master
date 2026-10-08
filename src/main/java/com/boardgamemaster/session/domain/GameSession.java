package com.boardgamemaster.session.domain;

import java.time.Instant;
import java.util.*;
import com.boardgamemaster.setup.domain.*;
import static com.boardgamemaster.session.domain.SessionException.Code.INVALID_SESSION_ACTION;

public record GameSession(UUID id, UUID gameId, UUID gameVersionId, UUID teachingFlowId, int playerCount,
        TeachingDepth teachingDepth, SessionStage stage, Status status, String currentSectionKey,
        Set<String> completedSections, long revision, Instant createdAt, Instant endedAt, SetupProgress setup) {
    public GameSession(UUID id, UUID gameId, UUID gameVersionId, UUID teachingFlowId, int playerCount,
            TeachingDepth teachingDepth, SessionStage stage, Status status, String currentSectionKey,
            Set<String> completedSections, long revision, Instant createdAt, Instant endedAt) {
        this(id, gameId, gameVersionId, teachingFlowId, playerCount, teachingDepth, stage, status,
                currentSectionKey, completedSections, revision, createdAt, endedAt, null);
    }
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
        if (action == Action.CONTINUE && sections.get(index).setupTrigger() && (setup == null || !setup.completed())) {
            throw invalid("Complete guided setup before continuing this lesson");
        }
        var completed = new HashSet<>(completedSections);
        var nextStage = stage;
        if (action == Action.CONTINUE) {
            completed.add(currentSectionKey);
            if (index + 1 < sections.size()) { index++; } else { nextStage = SessionStage.PLAYING; }
        } else if (action == Action.PREVIOUS && index > 0) { index--; }
        return new GameSession(id, gameId, gameVersionId, teachingFlowId, playerCount, teachingDepth, nextStage,
                status, sections.get(index).key(), completed, revision + 1, createdAt, endedAt, setup);
    }
    public GameSession complete(Instant now) {
        requireActive();
        return new GameSession(id, gameId, gameVersionId, teachingFlowId, playerCount, teachingDepth,
                SessionStage.COMPLETED, Status.COMPLETED, currentSectionKey, completedSections, revision + 1, createdAt, now, setup);
    }
    public GameSession startSetup(SetupFlow flow, boolean restart) {
        requireActive(); requireSetupFlow(flow);
        if (stage != SessionStage.TEACHING && !(restart && stage == SessionStage.SETUP)) { throw invalid("Setup can start only while teaching"); }
        if (setup != null && setup.currentStepKey() != null && !restart) { throw invalid("Setup already started; use restart to begin again"); }
        if (restart && (setup == null || setup.currentStepKey() == null)) { throw invalid("Setup has not started yet"); }
        String returnKey = stage == SessionStage.SETUP ? setup.returnSectionKey() : currentSectionKey;
        var progress = (setup == null ? SetupProgress.pinned(flow.id()) : setup).start(flow, returnKey);
        return withSetup(SessionStage.SETUP, currentSectionKey, progress);
    }
    public GameSession navigateSetup(SetupFlow flow, SetupProgress.Action action) {
        requireSetupInProgress(flow);
        return withSetup(stage, currentSectionKey, setup.navigate(flow, action));
    }
    public GameSession finishSetup(SetupFlow flow) {
        requireSetupInProgress(flow);
        var progress = setup.finish(flow);
        return withSetup(SessionStage.TEACHING, progress.returnSectionKey(), progress);
    }
    private void requireSetupInProgress(SetupFlow flow) {
        requireActive(); requireSetupFlow(flow);
        if (stage != SessionStage.SETUP || setup == null) { throw invalid("Setup navigation requires the SETUP stage"); }
    }
    private void requireSetupFlow(SetupFlow flow) {
        if (flow == null || !gameVersionId.equals(flow.gameVersionId()) || (setup != null && !setup.flowId().equals(flow.id()))) {
            throw invalid("Setup flow must belong to the session's pinned version and flow");
        }
    }
    private GameSession withSetup(SessionStage nextStage, String lesson, SetupProgress progress) {
        return new GameSession(id, gameId, gameVersionId, teachingFlowId, playerCount, teachingDepth,
                nextStage, status, lesson, completedSections, revision + 1, createdAt, endedAt, progress);
    }
    private static SessionException invalid(String message) { return new SessionException(INVALID_SESSION_ACTION, message); }
}
