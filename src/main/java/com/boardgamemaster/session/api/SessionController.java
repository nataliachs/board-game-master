package com.boardgamemaster.session.api;

import com.boardgamemaster.session.application.SessionService;
import com.boardgamemaster.session.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionService sessions;
    public SessionController(SessionService sessions) { this.sessions = sessions; }
    public record CreateRequest(@NotNull UUID gameId, @NotNull @Min(1) Integer playerCount, TeachingDepth teachingDepth) { }
    public record LessonAction(@NotNull GameSession.Action action, @NotNull @Min(0) Long expectedRevision) { }
    public record CompleteRequest(@NotNull @Min(0) Long expectedRevision) { }
    public record MessageRequest(@NotBlank @Size(max = 4000) String question) { }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Session created; Location identifies the persisted session")
    @Operation(summary = "Create a teaching-first session with a pinned ruleset and flow; STANDARD is the default depth")
    public ResponseEntity<SessionService.LessonView> create(@Valid @RequestBody CreateRequest request) {
        var view = sessions.create(request.gameId(), request.playerCount(), request.teachingDepth());
        return ResponseEntity.created(URI.create("/api/v1/sessions/" + view.session().id())).body(view);
    }
    @GetMapping("/{id}")
    public SessionService.LessonView get(@PathVariable UUID id) { return sessions.current(id); }
    @PostMapping("/{id}/teaching/actions")
    @Operation(summary = "Continue, go back, repeat or explain again; final CONTINUE enters PLAYING without creating another session")
    public SessionService.LessonView navigate(@PathVariable UUID id, @Valid @RequestBody LessonAction request) {
        return sessions.navigate(id, request.action(), request.expectedRevision());
    }
    @PostMapping("/{id}/complete")
    public SessionService.LessonView complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest request) {
        return sessions.complete(id, request.expectedRevision());
    }
    @PostMapping("/{id}/messages")
    @Operation(summary = "Ask a grounded question using bounded recent context without moving the lesson")
    public SessionService.MessageResult ask(@PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        return sessions.ask(id, request.question());
    }
    @GetMapping("/{id}/messages")
    @Operation(summary = "Read bounded history, oldest first; use the first sequenceNumber as beforeSequence for older pages")
    public List<ConversationMessage> history(@PathVariable UUID id, @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "50") int limit) { return sessions.history(id, beforeSequence, limit); }
}
