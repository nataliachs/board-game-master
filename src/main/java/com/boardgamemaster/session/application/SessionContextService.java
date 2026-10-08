package com.boardgamemaster.session.application;

import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.rag.application.AnswerConversation;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.session.infrastructure.SessionRepository;
import java.util.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded recent-history policy; future summarization belongs here, never in controllers. */
@Service
@EnableConfigurationProperties(SessionProperties.class)
public class SessionContextService {
    public record Context(GameSession session, TeachingFlow flow, TeachingSection lesson, AnswerConversation conversation) { }
    private final SessionRepository repository;
    private final GameService games;
    private final SessionProperties config;
    private final com.boardgamemaster.setup.infrastructure.SetupRepository setups;
    public SessionContextService(SessionRepository repository, GameService games, SessionProperties config, com.boardgamemaster.setup.infrastructure.SetupRepository setups) {
        this.setups = setups;
        this.repository = repository; this.games = games; this.config = config;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Context load(UUID id) {
        var session = repository.session(id, false);
        var game = games.getGame(session.gameId());
        var version = games.getVersion(session.gameId(), session.gameVersionId());
        var flow = repository.flow(session.teachingFlowId());
        var lesson = flow.sectionsFor(session.teachingDepth()).stream().filter(s -> s.key().equals(session.currentSectionKey())).findFirst().orElseThrow();
        var recent = bound(repository.messages(id, Long.MAX_VALUE, config.recentMessageLimit()), config.contextCharacters());
        AnswerConversation.SetupContext setupContext = null;
        if (session.stage() == SessionStage.SETUP && session.setup() != null) {
            var setupFlow = setups.flow(session.setup().flowId());
            var step = setupFlow.steps().stream().filter(s -> s.key().equals(session.setup().currentStepKey())).findFirst().orElseThrow();
            setupContext = new AnswerConversation.SetupContext(step.key(), step.title(), step.instruction());
        }
        return new Context(session, flow, lesson, new AnswerConversation(id, game.getName(), version.getEdition(),
                version.getLanguage(), session.playerCount(), session.teachingDepth().name(), session.stage().name(),
                lesson.title(), lesson.explanation(), recent, setupContext));
    }
    static List<AnswerConversation.Turn> bound(List<ConversationMessage> messages, int characters) {
        var selected = new ArrayList<AnswerConversation.Turn>();
        int used = 0;
        for (var message : messages.reversed()) {
            if (used + message.content().length() > characters) { break; }
            selected.add(new AnswerConversation.Turn(message.role().name(), message.content()));
            used += message.content().length();
        }
        return selected.reversed().stream().toList();
    }
}
