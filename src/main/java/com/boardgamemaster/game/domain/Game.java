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
    @Column(length = 40)
    private String category;
    @Column(length = 500)
    private String coverImageUrl;

    protected Game() { }

    public Game(String slug, String name, String publisher, int minPlayers, int maxPlayers,
            int estimatedDurationMinutes, String description, boolean active) {
        this(slug, name, publisher, minPlayers, maxPlayers, estimatedDurationMinutes, description, active, null, null);
    }

    public Game(String slug, String name, String publisher, int minPlayers, int maxPlayers,
            int estimatedDurationMinutes, String description, boolean active, String category, String coverImageUrl) {
        this.slug = CatalogValidation.required(slug, 100, "Slug", INVALID_GAME);
        if (!this.slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new CatalogException(INVALID_GAME, "Slug must contain lowercase letters, digits, and single hyphens");
        }
        updateDetails(name, publisher, minPlayers, maxPlayers, estimatedDurationMinutes, description, active, category, coverImageUrl);
    }

    /** Replaces the editable metadata; the slug and identity never change. */
    public void updateDetails(String name, String publisher, int minPlayers, int maxPlayers,
            int estimatedDurationMinutes, String description, boolean active, String category, String coverImageUrl) {
        String name0 = CatalogValidation.required(name, 200, "Name", INVALID_GAME);
        String publisher0 = CatalogValidation.optional(publisher, 200, "Publisher", INVALID_GAME);
        String description0 = CatalogValidation.optional(description, 4000, "Description", INVALID_GAME);
        String category0 = CatalogValidation.optional(category, 40, "Category", INVALID_GAME);
        String cover0 = CatalogValidation.optional(coverImageUrl, 500, "Cover image URL", INVALID_GAME);
        if (cover0 != null && !cover0.matches("(https://|/)[^\\s\"'<>]+")) {
            throw new CatalogException(INVALID_GAME, "Cover image URL must be an https:// URL or a path starting with /");
        }
        if (minPlayers < 1 || maxPlayers < minPlayers) {
            throw new CatalogException(INVALID_GAME, "Player limits must be positive and minPlayers must not exceed maxPlayers");
        }
        if (estimatedDurationMinutes < 1) {
            throw new CatalogException(INVALID_GAME, "Estimated duration must be positive");
        }
        this.name = name0;
        this.publisher = publisher0;
        this.description = description0;
        this.category = category0;
        this.coverImageUrl = cover0;
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
    public String getCategory() { return category; }
    public String getCoverImageUrl() { return coverImageUrl; }
}
