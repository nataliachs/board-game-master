package com.boardgamemaster.game.api;

import com.boardgamemaster.game.api.GameRequests.*;
import com.boardgamemaster.game.api.GameResponses.*;
import com.boardgamemaster.game.application.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/games")
@Tag(name = "Games", description = "Game metadata, supported versions, and default ruleset selection")
public class GameController {
    private final GameService service;

    public GameController(GameService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "List games", description = "Includes active and inactive metadata. Page is zero-based; size is 1–100.")
    public GamePage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return GamePage.from(service.listGames(page, size));
    }

    @PostMapping
    @Operation(summary = "Register a game")
    public ResponseEntity<GameResponse> create(@Valid @RequestBody CreateGame request) {
        var game = service.createGame(request.slug(), request.name(), request.publisher(), request.minPlayers(),
                request.maxPlayers(), request.estimatedDurationMinutes(), request.description(), request.active(),
                request.category(), request.coverImageUrl());
        return ResponseEntity.created(URI.create("/api/v1/games/" + game.getId())).body(GameResponse.from(game));
    }

    @GetMapping("/{gameId}")
    @Operation(summary = "Read game metadata")
    public GameResponse get(@PathVariable UUID gameId) { return GameResponse.from(service.getGame(gameId)); }

    @PostMapping("/{gameId}/versions")
    @Operation(summary = "Register a supported version", description = "defaultVersion=true atomically replaces the previous default. Only active versions may be defaults.")
    public ResponseEntity<VersionResponse> createVersion(@PathVariable UUID gameId, @Valid @RequestBody CreateVersion request) {
        var version = service.createVersion(gameId, request.edition(), request.versionLabel(), request.language(),
                request.releaseYear(), request.active(), request.defaultVersion());
        return ResponseEntity.created(URI.create("/api/v1/games/" + gameId + "/versions/" + version.getId()))
                .body(VersionResponse.from(version));
    }

    @GetMapping("/{gameId}/versions")
    @Operation(summary = "List a game's supported versions")
    public List<VersionResponse> versions(@PathVariable UUID gameId) {
        return service.listVersions(gameId).stream().map(VersionResponse::from).toList();
    }

    @GetMapping("/{gameId}/versions/{versionId}")
    @Operation(summary = "Read a version belonging to this game")
    public VersionResponse version(@PathVariable UUID gameId, @PathVariable UUID versionId) {
        return VersionResponse.from(service.getVersion(gameId, versionId));
    }

    @GetMapping("/{gameId}/default-version")
    @Operation(summary = "Resolve the default supported version", description = "Requires an active game and default version. Optionally validates playerCount without creating a session.")
    public VersionResponse defaultVersion(@PathVariable UUID gameId, @RequestParam(required = false) Integer playerCount) {
        return VersionResponse.from(service.resolveDefaultVersion(gameId, playerCount));
    }

    @PutMapping("/{gameId}/default-version")
    @Operation(summary = "Select this game's default version", description = "Version must belong to this game and be active. Selection is idempotent and atomic.")
    public VersionResponse setDefault(@PathVariable UUID gameId, @Valid @RequestBody SetDefaultVersion request) {
        return VersionResponse.from(service.setDefaultVersion(gameId, request.gameVersionId()));
    }
}
