package com.boardgamemaster.ui.application;

import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.game.api.GameResponses.*;
import com.boardgamemaster.session.application.*;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.setup.application.SetupService;
import com.boardgamemaster.setup.infrastructure.SetupRepository;
import com.boardgamemaster.setup.domain.SetupStep;
import com.boardgamemaster.ai.infrastructure.*;
import com.boardgamemaster.voice.application.VoiceProperties;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DemoUiService {
    public record GamesPage(List<GameResponse> games, boolean hasNext) { }
    public record Capabilities(boolean inputEnabled, boolean outputEnabled, int maxAudioBytes) { }
    public record Page(GameResponse game, VersionResponse version, SessionService.LessonView lesson, SetupService.View setup,
            List<TeachingSection> lessons, List<SetupStep> setupSteps, List<ConversationMessage> history, Long olderCursor, Capabilities voice) { }
    private final GameService games;
    private final SessionService sessions;
    private final SessionContextService contexts;
    private final SetupService setups;
    private final SetupRepository setupRepository;
    private final Capabilities voice;
    public DemoUiService(GameService games, SessionService sessions, SessionContextService contexts, SetupService setups,
            SetupRepository setupRepository, SttProperties stt, TtsProperties tts, VoiceProperties config) {
        this.games = games; this.sessions = sessions; this.contexts = contexts; this.setups = setups; this.setupRepository = setupRepository;
        voice = new Capabilities(!stt.provider().equals("disabled"), !tts.provider().equals("disabled"), config.maxAudioBytes());
    }
    @Transactional(readOnly = true)
    public GamesPage games(int page) {
        var result = games.listGames(page, 100);
        return new GamesPage(result.stream().filter(g -> g.isActive()).map(GameResponse::from).toList(), result.hasNext());
    }
    public Capabilities capabilities() { return voice; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(UUID id, Long before) {
        var context = contexts.load(id); var session = context.session();
        var history = sessions.history(id, before, 20);
        var setup = session.setup() == null ? null : setups.current(id);
        var steps = setup == null ? List.<SetupStep>of() : setupRepository.flow(setup.flowId()).steps();
        return new Page(GameResponse.from(games.getGame(session.gameId())), VersionResponse.from(games.getVersion(session.gameId(), session.gameVersionId())),
                sessions.current(id), setup, context.flow().sectionsFor(session.teachingDepth()), steps, history,
                history.size() == 20 && history.getFirst().sequenceNumber() > 1 ? history.getFirst().sequenceNumber() : null, voice);
    }
}
