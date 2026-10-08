package com.boardgamemaster.session.api;

import com.boardgamemaster.session.application.TeachingService;
import com.boardgamemaster.session.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.Operation;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/game-versions/{versionId}/teaching-flows")
public class TeachingController {
    private final TeachingService teaching;
    public TeachingController(TeachingService teaching) { this.teaching = teaching; }
    public record FlowRequest(@NotBlank @Size(max = 200) String name,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid TeachingSection> sections) { }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publish immutable teaching content; used by future sessions, existing sessions keep their flow")
    public TeachingFlow publish(@PathVariable UUID versionId, @Valid @RequestBody FlowRequest request) {
        return teaching.publish(versionId, request.name(), request.sections());
    }
    @GetMapping
    public TeachingFlow current(@PathVariable UUID versionId) { return teaching.current(versionId); }
}
