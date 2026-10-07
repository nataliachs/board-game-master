package com.boardgamemaster.rag.api;

import com.boardgamemaster.rag.application.RuleEmbeddingService;
import com.boardgamemaster.rag.application.RuleRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/game-versions/{versionId}")
public class RuleSearchController {
    private final RuleEmbeddingService indexing;
    private final RuleRetrievalService retrieval;
    public RuleSearchController(RuleEmbeddingService indexing, RuleRetrievalService retrieval) { this.indexing = indexing; this.retrieval = retrieval; }

    public record SearchRequest(@NotBlank @Size(max = 16000) String query, @Min(1) @Max(50) Integer topK) { }

    @Operation(summary = "Index a document's missing embeddings",
            description = "Explicit provider operation; may incur provider charges. Existing vectors for the active profile are reused. Does not generate answers.")
    @PostMapping("/documents/{documentId}/embeddings")
    public RuleEmbeddingService.IndexResult index(@PathVariable UUID versionId, @PathVariable UUID documentId) {
        return indexing.index(versionId, documentId);
    }

    @GetMapping("/documents/{documentId}/embeddings")
    public RuleEmbeddingService.IndexResult status(@PathVariable UUID versionId, @PathVariable UUID documentId) {
        return indexing.status(versionId, documentId);
    }

    @Operation(summary = "Retrieve matching rule chunks for one game version",
            description = "Exact cosine ranking within the active embedding profile. Returns source metadata and similarity, not a generated answer or confidence label.")
    @PostMapping("/rules/search")
    public RuleRetrievalService.SearchResult search(@PathVariable UUID versionId, @Valid @RequestBody SearchRequest request) {
        return retrieval.search(versionId, request.query(), request.topK());
    }
}
