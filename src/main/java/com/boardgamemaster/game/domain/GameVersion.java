package com.boardgamemaster.game.domain;

import static com.boardgamemaster.game.domain.CatalogException.Code.*;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "game_versions")
public class GameVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private UUID gameId;
    @Column(nullable = false, length = 100)
    private String edition;
    @Column(nullable = false, length = 100)
    private String versionLabel;
    @Column(nullable = false, length = 35)
    private String language;
    private Integer releaseYear;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private boolean defaultVersion;

    protected GameVersion() { }

    public GameVersion(UUID gameId, String edition, String versionLabel, String language,
            Integer releaseYear, boolean active) {
        if (gameId == null) { throw new CatalogException(INVALID_GAME_VERSION, "Game ownership is required"); }
        this.gameId = gameId;
        this.edition = CatalogValidation.required(edition, 100, "Edition", INVALID_GAME_VERSION);
        this.versionLabel = CatalogValidation.required(versionLabel, 100, "Version label", INVALID_GAME_VERSION);
        this.language = CatalogValidation.required(language, 35, "Language", INVALID_GAME_VERSION)
                .toLowerCase(Locale.ROOT);
        if (!this.language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) {
            throw new CatalogException(INVALID_GAME_VERSION, "Language must be a tag such as en, id, or en-US");
        }
        if (releaseYear != null && (releaseYear < 1 || releaseYear > 9999)) {
            throw new CatalogException(INVALID_GAME_VERSION, "Release year must be between 1 and 9999");
        }
        this.releaseYear = releaseYear;
        this.active = active;
    }

    public void makeDefault() {
        requireActive();
        defaultVersion = true;
    }

    public void clearDefault() { defaultVersion = false; }

    public void requireActive() {
        if (!active) { throw new CatalogException(GAME_VERSION_INACTIVE, "Game version is not active"); }
    }

    public UUID getId() { return id; }
    public UUID getGameId() { return gameId; }
    public String getEdition() { return edition; }
    public String getVersionLabel() { return versionLabel; }
    public String getLanguage() { return language; }
    public Integer getReleaseYear() { return releaseYear; }
    public boolean isActive() { return active; }
    public boolean isDefaultVersion() { return defaultVersion; }
}
