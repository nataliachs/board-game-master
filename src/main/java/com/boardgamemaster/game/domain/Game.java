package com.boardgamemaster.game.domain;

import static com.boardgamemaster.game.domain.CatalogException.Code.*;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "games")
public class Game {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, length = 100, unique = true)
    private String slug;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 200)
    private String publisher;
    @Column(nullable = false)
    private int minPlayers;
    @Column(nullable = false)
    private int maxPlayers;
    @Column(nullable = false)
    private int estimatedDurationMinutes;
    @Column(length = 4000)
    private String description;
    @Column(nullable = false)
    private boolean active;

    protected Game() { }

    public Game(String slug, String name, String publisher, int minPlayers, int maxPlayers,
            int estimatedDurationMinutes, String description, boolean active) {
        this.slug = CatalogValidation.required(slug, 100, "Slug", INVALID_GAME);
        if (!this.slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new CatalogException(INVALID_GAME, "Slug must contain lowercase letters, digits, and single hyphens");
        }
        this.name = CatalogValidation.required(name, 200, "Name", INVALID_GAME);
        this.publisher = CatalogValidation.optional(publisher, 200, "Publisher", INVALID_GAME);
        this.description = CatalogValidation.optional(description, 4000, "Description", INVALID_GAME);
        if (minPlayers < 1 || maxPlayers < minPlayers) {
            throw new CatalogException(INVALID_GAME, "Player limits must be positive and minPlayers must not exceed maxPlayers");
        }
        if (estimatedDurationMinutes < 1) {
            throw new CatalogException(INVALID_GAME, "Estimated duration must be positive");
        }
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.estimatedDurationMinutes = estimatedDurationMinutes;
        this.active = active;
    }

    public void requireActive() {
        if (!active) { throw new CatalogException(GAME_INACTIVE, "Game is not active"); }
    }

    public void validatePlayerCount(int count) {
        if (count < minPlayers || count > maxPlayers) {
            throw new CatalogException(INVALID_PLAYER_COUNT,
                    "Player count must be between " + minPlayers + " and " + maxPlayers);
        }
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getPublisher() { return publisher; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getEstimatedDurationMinutes() { return estimatedDurationMinutes; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
}
