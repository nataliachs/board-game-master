package com.boardgamemaster.setup.application;

import com.boardgamemaster.game.domain.CatalogException;
import com.boardgamemaster.game.infrastructure.GameVersionRepository;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.session.infrastructure.SessionRepository;
import com.boardgamemaster.setup.domain.*;
import com.boardgamemaster.setup.infrastructure.SetupRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class SetupService {
    public enum Action { START, NEXT, PREVIOUS, REPEAT, RESTART, COMPLETE }
    public record View(GameSession session, UUID flowId, String flowName, SetupStep step, int stepNumber, int totalSteps, String notice) { }
    private final SetupRepository setups;
    private final SessionRepository sessions;
    private final GameVersionRepository versions;
    public SetupService(SetupRepository setups, SessionRepository sessions, GameVersionRepository versions) {
        this.setups = setups; this.sessions = sessions; this.versions = versions;
    }
    @Transactional
    public SetupFlow publish(UUID version, String name, List<SetupStep> steps) {
        versions.findById(version).orElseThrow(() -> new CatalogException(CatalogException.Code.GAME_VERSION_NOT_FOUND, "Game version was not found")).requireActive();
        var flow = new SetupFlow(UUID.randomUUID(), version, name, Instant.now(), steps);
        setups.insert(flow);
        return flow;
    }
    @Transactional(readOnly = true)
    public SetupFlow latest(UUID version) {
        versions.findById(version).orElseThrow(() -> new CatalogException(CatalogException.Code.GAME_VERSION_NOT_FOUND, "Game version was not found"));
        return setups.requireLatest(version);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View current(UUID id) { return view(sessions.session(id, false)); }
    @Transactional
    public View act(UUID id, Action action, long revision) {
        var session = sessions.session(id, true);
        if (session.revision() != revision) { throw new SessionException(SessionException.Code.SESSION_CONFLICT, "Session changed; reload before repeating an action"); }
        session.requireActive();
        var flow = flow(session);
        var next = switch (action) {
            case START -> session.startSetup(flow, false);
            case RESTART -> session.startSetup(flow, true);
            case COMPLETE -> session.finishSetup(flow);
            default -> session.navigateSetup(flow, SetupProgress.Action.valueOf(action.name()));
        };
        sessions.updateSession(next, session.revision());
        return view(next);
    }
    private SetupFlow flow(GameSession session) {
        return session.setup() == null ? setups.requireLatest(session.gameVersionId()) : setups.flow(session.setup().flowId());
    }
    private View view(GameSession session) {
        var flow = flow(session);
        String key = session.setup() == null ? null : session.setup().currentStepKey();
        var step = key == null ? null : flow.steps().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
        String notice = session.status() == GameSession.Status.COMPLETED ? "Session completed. Setup progress remains available."
                : session.setup() != null && session.setup().completed() ? "Setup confirmation saved. Return to teaching; this does not verify the physical board."
                : key == null ? "Start setup when ready. Each Next confirms the current step; Complete confirms the final step."
                : "Follow this step, then choose Next. At the final step choose Complete to return to your saved lesson.";
        return new View(session, flow.id(), flow.name(), step, step == null ? 0 : flow.steps().indexOf(step) + 1, flow.steps().size(), notice);
    }
}
