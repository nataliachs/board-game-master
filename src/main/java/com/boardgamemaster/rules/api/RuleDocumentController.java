package com.boardgamemaster.rules.api;

import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.application.RuleIngestionProperties;
import com.boardgamemaster.rules.domain.RuleImportException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.boardgamemaster.rules.api.RuleResponses.*;
import static com.boardgamemaster.rules.domain.RuleImportException.Code.*;

@RestController
@RequestMapping("/api/v1/game-versions/{versionId}/documents")
public class RuleDocumentController {
    private final RuleDocumentService service;
    private final RuleIngestionProperties limits;

    public RuleDocumentController(RuleDocumentService service, RuleIngestionProperties limits) {
        this.service = service;
        this.limits = limits;
    }

    @Operation(summary = "Import a permitted UTF-8 text or Markdown rule source",
            description = "Upload file (.txt/.md) and metadata (application/json). Import only sources you have permission to use. No server filesystem paths or URLs are fetched.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Document> upload(@PathVariable UUID versionId,
            @Valid @RequestPart RuleRequests.ImportMetadata metadata, @RequestPart MultipartFile file) {
        if (file.getSize() > limits.maxBytes()) {
            throw new RuleImportException(DOCUMENT_TOO_LARGE, "Document exceeds configured byte limit");
        }
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException exception) { throw new RuleImportException(INVALID_DOCUMENT, "Uploaded file could not be read"); }
        var result = service.ingest(versionId, metadata.toDomain(), file.getOriginalFilename(), bytes);
        return ResponseEntity.created(URI.create("/api/v1/game-versions/" + versionId + "/documents/" + result.getId()))
                .body(Document.from(result));
    }

    @GetMapping
    public PageResult<Document> list(@PathVariable UUID versionId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResult.from(service.listDocuments(versionId, page, size).map(Document::from));
    }

    @GetMapping("/{documentId}")
    public Document get(@PathVariable UUID versionId, @PathVariable UUID documentId) {
        return Document.from(service.getDocument(versionId, documentId));
    }

    @Operation(summary = "Read cleaned source text; chunk offsets use zero-based UTF-16 indices with an exclusive end")
    @GetMapping("/{documentId}/source")
    public Source source(@PathVariable UUID versionId, @PathVariable UUID documentId) {
        var document = service.getDocument(versionId, documentId);
        return new Source(documentId, versionId, document.getNormalizedText());
    }

    @GetMapping("/{documentId}/chunks")
    public PageResult<Chunk> chunks(@PathVariable UUID versionId, @PathVariable UUID documentId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResult.from(service.listChunks(versionId, documentId, page, size).map(Chunk::from));
    }
}
