package com.boardgamemaster.session.application;

import com.boardgamemaster.game.domain.CatalogException;
import com.boardgamemaster.game.infrastructure.GameVersionRepository;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.session.infrastructure.SessionRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeachingService {
    private final SessionRepository repository;
    private final GameVersionRepository versions;
    private final com.boardgamemaster.setup.infrastructure.SetupRepository setups;
    public TeachingService(SessionRepository repository, GameVersionRepository versions, com.boardgamemaster.setup.infrastructure.SetupRepository setups) { this.setups = setups; this.repository = repository; this.versions = versions; }
    @Transactional
    public TeachingFlow publish(UUID version, String name, List<TeachingSection> sections) {
        versions.findById(version).orElseThrow(() -> new CatalogException(CatalogException.Code.GAME_VERSION_NOT_FOUND, "Game version was not found")).requireActive();
        var flow = new TeachingFlow(UUID.randomUUID(), version, name, Instant.now(), sections);
        if (flow.sections().stream().anyMatch(TeachingSection::setupTrigger)) { setups.requireLatest(version); }
        repository.insertFlow(flow);
        return flow;
    }
    @Transactional(readOnly = true)
    public TeachingFlow current(UUID version) {
        versions.findById(version).orElseThrow(() -> new CatalogException(CatalogException.Code.GAME_VERSION_NOT_FOUND, "Game version was not found"));
        return repository.latestFlow(version);
    }
}
