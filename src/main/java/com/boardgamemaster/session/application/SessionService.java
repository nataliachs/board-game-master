package com.boardgamemaster.session.application;

import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.session.infrastructure.SessionRepository;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.boardgamemaster.session.domain.SessionException.Code.INVALID_SESSION;

@Service
public class SessionService {
    public record LessonView(GameSession session, TeachingSection lesson, int lessonNumber, int totalLessons,
            boolean readyToPlay, String explanation, String notice) { }
    public record MessageResult(UUID sessionId, long revision, String currentSectionKey, RuleAnswer answer) { }
    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);
    private final SessionRepository repository;
    private final SessionContextService contexts;
    private final GameService games;
    private final RuleAnswerService answers;
    private final TransactionTemplate transaction;
    public SessionService(SessionRepository repository, SessionContextService contexts, GameService games,
            RuleAnswerService answers, PlatformTransactionManager manager) {
        this.repository = repository; this.contexts = contexts; this.games = games; this.answers = answers;
        transaction = new TransactionTemplate(manager);
    }
    public LessonView create(UUID gameId, Integer players, TeachingDepth depth) {
        if (gameId == null || players == null || players < 1) { throw new SessionException(INVALID_SESSION, "Game and positive player count are required"); }
        UUID id = transaction.execute(status -> {
            var version = games.resolveDefaultVersion(gameId, players);
            var flow = repository.latestFlow(version.getId());
            var selectedDepth = depth == null ? TeachingDepth.STANDARD : depth;
            var session = new GameSession(UUID.randomUUID(), gameId, version.getId(), flow.id(), players, selectedDepth,
                    SessionStage.TEACHING, GameSession.Status.ACTIVE, flow.sectionsFor(selectedDepth).getFirst().key(), Set.of(), 0, Instant.now(), null);
            repository.insertSession(session);
            return session.id();
        });
        LOG.info("Game session created: sessionId={}, gameId={}", id, gameId);
        return current(id);
    }
    public LessonView current(UUID id) { return view(contexts.load(id)); }
    private LessonView view(SessionContextService.Context context) { return view(context, false); }
    private LessonView view(SessionContextService.Context context, boolean explainAgain) {
        var applicable = context.flow().sectionsFor(context.session().teachingDepth());
        String explanation = context.lesson().explanation();
        if (explainAgain) {
            explanation = "Let's take this one step at a time. " + explanation;
            for (var example : context.lesson().examples()) { explanation += "\nExample: " + example; }
            for (var tip : context.lesson().tips()) { explanation += "\nTip: " + tip; }
        }
        return new LessonView(context.session(), context.lesson(), applicable.indexOf(context.lesson()) + 1, applicable.size(),
                context.session().stage() == SessionStage.PLAYING,
                explanation,
                context.session().stage() == SessionStage.PLAYING ? "Teaching complete. Ready to play; ask rule questions in this same session. Guided setup is not implemented yet."
                        : context.session().stage() == SessionStage.COMPLETED ? "Session completed. Your lessons and conversation history remain available."
                        : "Guided physical setup is not available yet. This teaching flow does not confirm setup completion.");
    }
    public LessonView navigate(UUID id, GameSession.Action action, long expectedRevision) {
        transaction.executeWithoutResult(status -> {
            var session = locked(id, expectedRevision);
            repository.updateSession(session.navigate(repository.flow(session.teachingFlowId()), action), session.revision());
        });
        return view(contexts.load(id), action == GameSession.Action.EXPLAIN_AGAIN);
    }
    public LessonView complete(UUID id, long expectedRevision) {
        transaction.executeWithoutResult(status -> {
            var session = locked(id, expectedRevision);
            repository.updateSession(session.complete(Instant.now()), session.revision());
        });
        return current(id);
    }
    private GameSession locked(UUID id, long revision) {
        var session = repository.session(id, true);
        if (session.revision() != revision) { throw new SessionException(SessionException.Code.SESSION_CONFLICT, "Session changed; reload before repeating an action"); }
        return session;
    }
    // Provider calls happen outside database transactions. Revision comparison prevents stale context writes.
    public MessageResult ask(UUID id, String question) {
        if (question == null || question.isBlank() || question.length() > 4000) { throw new SessionException(INVALID_SESSION, "Question must be nonblank and at most 4000 characters"); }
        long started = System.nanoTime();
        var context = contexts.load(id);
        var session = context.session();
        session.requireActive();
        var answer = answers.askPinned(session.gameId(), session.gameVersionId(), question.strip(), context.conversation());
        transaction.executeWithoutResult(status -> {
            repository.claimMessageRevision(id, session.revision());
            repository.appendExchange(id, question.strip(), answer);
        });
        LOG.info("Session question completed: sessionId={}, gameId={}, elapsedMs={}", id, session.gameId(), (System.nanoTime() - started) / 1_000_000);
        return new MessageResult(id, session.revision() + 1, session.currentSectionKey(), answer);
    }
    public List<ConversationMessage> history(UUID id, Long before, int limit) {
        if (limit < 1 || limit > 100 || (before != null && before < 1)) { throw new SessionException(INVALID_SESSION, "History limit must be 1–100 and beforeSequence must be positive"); }
        return transaction.execute(status -> {
            repository.session(id, false);
            return repository.messages(id, before == null ? Long.MAX_VALUE : before, limit);
        });
    }
}
