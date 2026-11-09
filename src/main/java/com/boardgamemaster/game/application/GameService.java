package com.boardgamemaster.game.application;

import static com.boardgamemaster.game.domain.CatalogException.Code.*;

import com.boardgamemaster.game.domain.CatalogException;
import com.boardgamemaster.game.domain.Game;
import com.boardgamemaster.game.domain.GameVersion;
import com.boardgamemaster.game.infrastructure.GameRepository;
import com.boardgamemaster.game.infrastructure.GameVersionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GameService {
    private final GameRepository games;
    private final GameVersionRepository versions;

    public GameService(GameRepository games, GameVersionRepository versions) {
        this.games = games;
        this.versions = versions;
    }

    public Page<Game> listGames(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new CatalogException(INVALID_PAGINATION, "Page must be nonnegative and size must be between 1 and 100");
        }
        return games.findAll(PageRequest.of(page, size, Sort.by("slug")));
    }

    public Game getGame(UUID id) {
        return games.findById(id).orElseThrow(() -> new CatalogException(GAME_NOT_FOUND, "Game was not found"));
    }

    @Transactional
    public Game createGame(String slug, String name, String publisher, int minPlayers, int maxPlayers,
            int estimatedDurationMinutes, String description, boolean active) {
        return createGame(slug, name, publisher, minPlayers, maxPlayers, estimatedDurationMinutes, description, active, null, null);
    }

    @Transactional(readOnly = true)
    public boolean slugExists(String slug) {
        return games.existsBySlug(slug);
    }

    @Transactional
    public Game createGame(String slug, String name, String publisher, int minPlayers, int maxPlayers,
            int estimatedDurationMinutes, String description, boolean active, String category, String coverImageUrl) {
        Game game = new Game(slug, name, publisher, minPlayers, maxPlayers, estimatedDurationMinutes, description, active,
                category, coverImageUrl);
        if (games.existsBySlug(game.getSlug())) {
            throw new CatalogException(GAME_SLUG_EXISTS, "A game with this slug already exists");
        }
        return games.saveAndFlush(game);
    }

    @Transactional
    public Game updateGameDetails(UUID gameId, String name, String publisher, int minPlayers, int maxPlayers,
            int estimatedDurationMinutes, String description, boolean active, String category, String coverImageUrl) {
        Game game = getGame(gameId);
        game.updateDetails(name, publisher, minPlayers, maxPlayers, estimatedDurationMinutes, description, active,
                category, coverImageUrl);
        return games.saveAndFlush(game);
    }

    public List<GameVersion> listVersions(UUID gameId) {
        getGame(gameId);
        return versions.findByGameIdOrderByEditionAscVersionLabelAscLanguageAsc(gameId);
    }

    public GameVersion getVersion(UUID gameId, UUID versionId) {
        getGame(gameId);
        return ownedVersion(gameId, versionId);
    }

    @Transactional
    public GameVersion createVersion(UUID gameId, String edition, String versionLabel, String language,
            Integer releaseYear, boolean active, boolean defaultVersion) {
        lockGame(gameId);
        GameVersion version = new GameVersion(gameId, edition, versionLabel, language, releaseYear, active);
        if (versions.existsByGameIdAndEditionIgnoreCaseAndVersionLabelIgnoreCaseAndLanguage(
                gameId, version.getEdition(), version.getVersionLabel(), version.getLanguage())) {
            throw new CatalogException(GAME_VERSION_EXISTS, "This edition, version label, and language already exist for the game");
        }
        if (defaultVersion) {
            version.makeDefault();
            clearCurrentDefault(gameId);
        }
        return versions.saveAndFlush(version);
    }

    @Transactional
    public GameVersion setDefaultVersion(UUID gameId, UUID versionId) {
        lockGame(gameId);
        GameVersion version = ownedVersion(gameId, versionId);
        version.requireActive();
        if (!version.isDefaultVersion()) {
            clearCurrentDefault(gameId);
            version.makeDefault();
            versions.flush();
        }
        return version;
    }

    public GameVersion resolveDefaultVersion(UUID gameId, Integer playerCount) {
        Game game = getGame(gameId);
        game.requireActive();
        if (playerCount != null) { game.validatePlayerCount(playerCount); }
        GameVersion version = versions.findByGameIdAndDefaultVersionTrue(gameId).orElseThrow(
                () -> new CatalogException(DEFAULT_VERSION_NOT_CONFIGURED, "Game has no configured default version"));
        version.requireActive();
        return version;
    }

    private void lockGame(UUID gameId) {
        games.findByIdForUpdate(gameId).orElseThrow(() -> new CatalogException(GAME_NOT_FOUND, "Game was not found"));
    }

    private GameVersion ownedVersion(UUID gameId, UUID versionId) {
        return versions.findByIdAndGameId(versionId, gameId).orElseThrow(
                () -> new CatalogException(GAME_VERSION_NOT_FOUND, "Version was not found for this game"));
    }

    private void clearCurrentDefault(UUID gameId) {
        versions.findByGameIdAndDefaultVersionTrue(gameId).ifPresent(GameVersion::clearDefault);
        // Clear the existing flag before setting another to satisfy the partial unique index.
        versions.flush();
    }
}
