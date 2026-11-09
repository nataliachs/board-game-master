package com.boardgamemaster.rag.application;

import com.boardgamemaster.ai.EmbeddingProfile;
import com.boardgamemaster.ai.infrastructure.EmbeddingProperties;
import com.boardgamemaster.rules.application.RuleDocumentService;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RuleEmbeddingService {
    private final RuleDocumentService documents;
    private final RuleVectorStore store;
    private final EmbeddingGateway embeddings;
    private final EmbeddingProperties config;

    public RuleEmbeddingService(RuleDocumentService documents, RuleVectorStore store, EmbeddingGateway embeddings, EmbeddingProperties config) {
        this.documents = documents; this.store = store; this.embeddings = embeddings; this.config = config;
    }

    public record IndexResult(UUID gameVersionId, UUID documentId, EmbeddingProfile profile,
            int totalChunks, int indexedChunks, int newlyIndexedChunks) { }

    public record Removed(String documentName, int passages) { }

    /** Removes a rules source with its passages and their vectors, so it no longer answers questions. */
    @org.springframework.transaction.annotation.Transactional
    public Removed remove(UUID versionId, UUID documentId) {
        var document = documents.getDocument(versionId, documentId);
        var removed = new Removed(document.getName(), document.getChunkCount());
        store.deleteDocument(versionId, documentId);
        documents.deleteDocument(versionId, documentId);
        return removed;
    }

    public IndexResult status(UUID versionId, UUID documentId) {
        documents.getDocument(versionId, documentId);
        var counts = store.counts(versionId, documentId, embeddings.profile());
        return new IndexResult(versionId, documentId, embeddings.profile(), counts.totalChunks(), counts.indexedChunks(), 0);
    }

    public IndexResult index(UUID versionId, UUID documentId) {
        documents.getDocument(versionId, documentId);
        embeddings.requireConfigured();
        var profile = embeddings.profile();
        var counts = store.counts(versionId, documentId, profile);
        if (counts.totalChunks() > config.maxDocumentChunks()) {
            throw new RetrievalException(RetrievalException.Code.INDEX_LIMIT_EXCEEDED, "Document exceeds the synchronous embedding chunk limit");
        }
        var pending = store.pending(versionId, documentId, profile);
        // Validate the entire input before any paid request or database mutation.
        pending.forEach(c -> embeddings.validateText(c.content()));
        var writes = new ArrayList<RuleVectorStore.VectorWrite>();
        for (var chunk : pending) { writes.add(new RuleVectorStore.VectorWrite(chunk.id(), embeddings.embed(chunk.content()))); }
        int added = writes.isEmpty() ? 0 : store.store(versionId, documentId, profile, writes);
        var after = store.counts(versionId, documentId, profile);
        return new IndexResult(versionId, documentId, profile, after.totalChunks(), after.indexedChunks(), added);
    }
}
