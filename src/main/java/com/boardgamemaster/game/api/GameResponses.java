package com.boardgamemaster.game.api;

import com.boardgamemaster.game.domain.Game;
import com.boardgamemaster.game.domain.GameVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class GameResponses {
    private GameResponses() { }

    public record GameResponse(UUID id, String slug, String name, String publisher, int minPlayers,
            int maxPlayers, int estimatedDurationMinutes, String description, boolean active,
            String category, String coverImageUrl) {
        public static GameResponse from(Game game) {
            return new GameResponse(game.getId(), game.getSlug(), game.getName(), game.getPublisher(),
                    game.getMinPlayers(), game.getMaxPlayers(), game.getEstimatedDurationMinutes(),
                    game.getDescription(), game.isActive(), game.getCategory(), game.getCoverImageUrl());
        }

        /** Up to two letters shown on the library card when no cover image is configured. */
        public String initials() {
            var words = name.strip().split("\\s+");
            if (words.length > 1 && !words[1].isEmpty()) {
                return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(java.util.Locale.ROOT);
            }
            return name.strip().substring(0, Math.min(2, name.strip().length()));
        }
    }

    public record VersionResponse(UUID id, UUID gameId, String edition, String versionLabel,
            String language, Integer releaseYear, boolean active, boolean defaultVersion) {
        public static VersionResponse from(GameVersion version) {
            return new VersionResponse(version.getId(), version.getGameId(), version.getEdition(),
                    version.getVersionLabel(), version.getLanguage(), version.getReleaseYear(),
                    version.isActive(), version.isDefaultVersion());
        }
    }

    public record GamePage(List<GameResponse> items, int page, int size, long totalElements, int totalPages) {
        public static GamePage from(Page<Game> games) {
            return new GamePage(games.getContent().stream().map(GameResponse::from).toList(),
                    games.getNumber(), games.getSize(), games.getTotalElements(), games.getTotalPages());
        }
    }
}
