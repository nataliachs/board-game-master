package com.boardgamemaster.game.api;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class GameRequests {
    private GameRequests() { }

    public record CreateGame(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String publisher,
            @NotNull @Min(1) Integer minPlayers,
            @NotNull @Min(1) Integer maxPlayers,
            @NotNull @Min(1) Integer estimatedDurationMinutes,
            @Size(max = 4000) String description,
            @NotNull Boolean active,
            @Size(max = 40) String category,
            @Size(max = 500) String coverImageUrl) { }

    public record CreateVersion(
            @NotBlank @Size(max = 100) String edition,
            @NotBlank @Size(max = 100) String versionLabel,
            @NotBlank @Size(max = 35) @Pattern(regexp = "[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*") String language,
            @Min(1) @Max(9999) Integer releaseYear,
            @NotNull Boolean active,
            boolean defaultVersion) { }

    public record SetDefaultVersion(@NotNull UUID gameVersionId) { }
}
