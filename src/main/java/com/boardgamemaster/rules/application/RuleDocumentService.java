package com.boardgamemaster.rules.application;

import com.boardgamemaster.game.domain.GameVersion;
import com.boardgamemaster.game.infrastructure.GameVersionRepository;
import com.boardgamemaster.rules.domain.*;
import com.boardgamemaster.rules.infrastructure.*;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.boardgamemaster.rules.domain.RuleImportException.Code.*;

@Service
@Transactional(readOnly = true)
public class RuleDocumentService {
    private final GameVersionRepository versions;
    private final RuleDocumentRepository documents;
    private final RuleChunkRepository chunks;
    private final TextRuleProcessor processor;
    private final RuleIngestionProperties limits;

    public RuleDocumentService(GameVersionRepository versions, RuleDocumentRepository documents,
            RuleChunkRepository chunks, TextRuleProcessor processor, RuleIngestionProperties limits) {
        this.versions = versions;
        this.documents = documents;
        this.chunks = chunks;
        this.processor = processor;
        this.limits = limits;
    }

    public GameVersion getVersion(UUID id) {
        return versions.findById(id).orElseThrow(() -> new RuleImportException(GAME_VERSION_NOT_FOUND, "Game version was not found"));
    }

    @Transactional
    public RuleDocument ingest(UUID versionId, DocumentMetadata metadata, String fileName, byte[] bytes) {
        var version = getVersion(versionId);
        if (metadata == null) { throw new RuleImportException(INVALID_DOCUMENT, "Document metadata is required"); }
        if (!version.getLanguage().equals(metadata.language())) {
            throw new RuleImportException(INVALID_DOCUMENT, "Document language must match the selected game version");
        }
        var processed = processor.process(fileName, bytes);
        var document = documents.save(new RuleDocument(versionId, metadata, processed.fileName(), processed.text(),
                limits.chunkSize(), limits.chunkOverlap(), processed.chunks().size()));
        int index = 0;
        for (var part : processed.chunks()) {
            chunks.save(new RuleChunk(document.getId(), versionId, part.content(), part.sectionTitle(), part.pageNumber(),
                    index++, part.startOffset(), part.endOffset()));
        }
        documents.flush();
        return document;
    }

    public RuleDocument getDocument(UUID versionId, UUID documentId) {
        getVersion(versionId);
        return documents.findByIdAndGameVersionId(documentId, versionId)
                .orElseThrow(() -> new RuleImportException(DOCUMENT_NOT_FOUND, "Document was not found for this game version"));
    }

    /**
     * Deletes a document and its passages. Vectors of those passages must be deleted first (they
     * belong to the retrieval module); past answers keep their own copies of any quotes they showed.
     */
    @Transactional
    public void deleteDocument(UUID versionId, UUID documentId) {
        var document = getDocument(versionId, documentId);
        chunks.deleteByDocumentIdAndGameVersionId(document.getId(), versionId);
        documents.delete(document);
        documents.flush();
    }

    public Page<RuleDocument> listDocuments(UUID versionId, int page, int size) {
        getVersion(versionId);
        return documents.findByGameVersionId(versionId, paging(page, size, Sort.by("importedAt", "id")));
    }

    public Page<RuleChunk> listChunks(UUID versionId, UUID documentId, int page, int size) {
        getDocument(versionId, documentId);
        return chunks.findByDocumentIdAndGameVersionId(documentId, versionId, paging(page, size, Sort.by("chunkIndex")));
    }

    private PageRequest paging(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > 100) { throw new RuleImportException(INVALID_PAGINATION, "Page must be non-negative and size between 1 and 100"); }
        return PageRequest.of(page, size, sort);
    }
}
