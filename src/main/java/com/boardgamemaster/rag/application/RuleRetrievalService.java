package com.boardgamemaster.rag.application;

import com.boardgamemaster.ai.EmbeddingProfile;
import com.boardgamemaster.rules.application.RuleDocumentService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(RetrievalProperties.class)
public class RuleRetrievalService {
    private static final Logger LOG = LoggerFactory.getLogger(RuleRetrievalService.class);
    private final RuleDocumentService documents;
    private final RuleVectorStore store;
    private final EmbeddingGateway embeddings;
    private final RetrievalProperties config;

    public RuleRetrievalService(RuleDocumentService documents, RuleVectorStore store, EmbeddingGateway embeddings, RetrievalProperties config) {
        this.documents = documents; this.store = store; this.embeddings = embeddings; this.config = config;
    }

    public record SearchResult(UUID gameVersionId, EmbeddingProfile profile, int topK, List<RuleVectorStore.Match> matches) { }

    public SearchResult search(UUID versionId, String query, Integer topK) {
        documents.getVersion(versionId);
        embeddings.validateText(query);
        int limit = topK == null ? config.topK() : topK;
        if (limit < 1 || limit > 50) { throw new RetrievalException(RetrievalException.Code.INVALID_SEARCH, "topK must be between 1 and 50"); }
        embeddings.requireConfigured();
        var profile = embeddings.profile();
        if (!store.hasEmbeddings(versionId, profile)) { return new SearchResult(versionId, profile, limit, List.of()); }
        long started = System.nanoTime();
        var vector = embeddings.embed(query.strip());
        var matches = store.search(versionId, profile, vector, limit);
        LOG.info("Rule retrieval completed: gameVersionId={}, matches={}, elapsedMs={}", versionId, matches.size(), (System.nanoTime() - started) / 1_000_000);
        return new SearchResult(versionId, profile, limit, matches);
    }
}
