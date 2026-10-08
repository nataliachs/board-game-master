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

    public record SearchRequest(@NotBlank @Size(max = 16000) String query, @Min(1) @Max(50) Integer topK,
            @DecimalMin("-1") @DecimalMax("1") Double similarityThreshold, @Valid com.boardgamemaster.rag.application.RetrievalFilters filters) { }

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
            description = "Version/profile and metadata-scoped cosine candidates, similarity threshold, duplicate suppression and bounded reranking. Returns sources and diagnostics; no generated answer. Expansions require explicit document selection.")
    @PostMapping("/rules/search")
    public RuleRetrievalService.SearchResult search(@PathVariable UUID versionId, @Valid @RequestBody SearchRequest request) {
        return retrieval.search(versionId, request.query(), request.topK(), request.similarityThreshold(), request.filters());
    }
}
