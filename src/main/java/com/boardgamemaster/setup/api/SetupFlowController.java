package com.boardgamemaster.setup.api;

import com.boardgamemaster.setup.application.SetupService;
import com.boardgamemaster.setup.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/game-versions/{versionId}/setup-flows")
public class SetupFlowController {
    public record Request(@NotBlank @Size(max = 200) String name, @NotEmpty @Size(max = 100) List<@NotNull @Valid SetupStep> steps) { }
    private final SetupService service;
    public SetupFlowController(SetupService service) { this.service = service; }
    @GetMapping
    public SetupFlow latest(@PathVariable UUID versionId) { return service.latest(versionId); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SetupFlow publish(@PathVariable UUID versionId, @Valid @RequestBody Request request) { return service.publish(versionId, request.name(), request.steps()); }
}
