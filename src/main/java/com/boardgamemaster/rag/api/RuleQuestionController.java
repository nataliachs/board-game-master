package com.boardgamemaster.rag.api;

import com.boardgamemaster.rag.application.RuleAnswer;
import com.boardgamemaster.rag.application.RuleAnswerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.*;
import com.boardgamemaster.rag.application.RetrievalFilters;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/games/{gameId}/questions")
public class RuleQuestionController {
    private final RuleAnswerService answers;
    public RuleQuestionController(RuleAnswerService answers) { this.answers = answers; }
    public record QuestionRequest(@NotBlank @Size(max = 4000) String question, @Min(1) @Max(50) Integer topK,
            @DecimalMin("-1") @DecimalMax("1") Double similarityThreshold, @Valid RetrievalFilters filters) { }

    @Operation(summary = "Ask a rule question using the game's active default version",
            description = "Retrieves indexed evidence and optionally generates an answer. May incur embedding and LLM charges. "
                    + "Returns answer, evidence-based confidence and validated sources; insufficient evidence returns LOW with a fixed fallback. No session is created.")
    @PostMapping
    public RuleAnswer ask(@PathVariable UUID gameId, @Valid @RequestBody QuestionRequest request) {
        return answers.ask(gameId, request.question(), request.topK(), request.similarityThreshold(), request.filters());
    }
}
