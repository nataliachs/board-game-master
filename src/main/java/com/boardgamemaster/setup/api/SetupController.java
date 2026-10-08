package com.boardgamemaster.setup.api;

import com.boardgamemaster.setup.application.SetupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/v1/sessions/{id}/setup")
public class SetupController {
    public record Request(@NotNull @Min(0) Long expectedRevision) { }
    private final SetupService service;
    public SetupController(SetupService service) { this.service = service; }
    @GetMapping
    public SetupService.View current(@PathVariable UUID id) { return service.current(id); }
    @PostMapping("/start")
    public SetupService.View start(@PathVariable UUID id, @Valid @RequestBody Request request) { return service.act(id, SetupService.Action.START, request.expectedRevision()); }
    @PostMapping("/next")
    public SetupService.View next(@PathVariable UUID id, @Valid @RequestBody Request request) { return service.act(id, SetupService.Action.NEXT, request.expectedRevision()); }
    @PostMapping("/previous")
    public SetupService.View previous(@PathVariable UUID id, @Valid @RequestBody Request request) { return service.act(id, SetupService.Action.PREVIOUS, request.expectedRevision()); }
    @PostMapping("/repeat")
    public SetupService.View repeat(@PathVariable UUID id, @Valid @RequestBody Request request) { return service.act(id, SetupService.Action.REPEAT, request.expectedRevision()); }
    @PostMapping("/restart")
    public SetupService.View restart(@PathVariable UUID id, @Valid @RequestBody Request request) { return service.act(id, SetupService.Action.RESTART, request.expectedRevision()); }
    @PostMapping("/complete")
    public SetupService.View complete(@PathVariable UUID id, @Valid @RequestBody Request request) { return service.act(id, SetupService.Action.COMPLETE, request.expectedRevision()); }
}
