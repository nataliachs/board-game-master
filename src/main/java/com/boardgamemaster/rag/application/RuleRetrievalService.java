package com.boardgamemaster.rag.application;

import com.boardgamemaster.ai.EmbeddingProfile;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.RuleSourceType;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RuleDuplicateSuppressor duplicates;
    private final RuleReranker reranker;

    public RuleRetrievalService(RuleDocumentService documents, RuleVectorStore store, EmbeddingGateway embeddings, RetrievalProperties config) {
        this(documents, store, embeddings, config, new RuleDuplicateSuppressor(), (query, candidates) -> candidates);
    }
    @Autowired
    public RuleRetrievalService(RuleDocumentService documents, RuleVectorStore store, EmbeddingGateway embeddings,
            RetrievalProperties config, RuleDuplicateSuppressor duplicates, RuleReranker reranker) {
        this.documents = documents; this.store = store; this.embeddings = embeddings; this.config = config;
        this.duplicates = duplicates; this.reranker = reranker;
    }
    public record Diagnostics(RetrievalFilters filters, double similarityThreshold, int candidateLimit, int candidates,
            int belowThreshold, int duplicatesRemoved, int returned, boolean candidateLimitReached, long elapsedMs) { }
    public record SearchResult(UUID gameVersionId, EmbeddingProfile profile, int topK, List<RuleVectorStore.Match> matches, Diagnostics diagnostics) {
        public SearchResult { matches = List.copyOf(matches); }
        public SearchResult(UUID versionId, EmbeddingProfile profile, int topK, List<RuleVectorStore.Match> matches) {
            this(versionId, profile, topK, matches, null);
        }
    }
    public SearchResult search(UUID versionId, String query, Integer topK) { return search(versionId, query, topK, null, null); }

    public SearchResult search(UUID versionId, String query, Integer topK, Double threshold, RetrievalFilters requested) {
        long started = System.nanoTime();
        var version = documents.getVersion(versionId);
        embeddings.validateText(query);
        int limit = topK == null ? config.topK() : topK;
        double minimum = threshold == null ? config.similarityThreshold() : threshold;
        if (limit < 1 || limit > 50 || !Double.isFinite(minimum) || minimum < -1 || minimum > 1) {
            throw new RetrievalException(RetrievalException.Code.INVALID_SEARCH, "topK must be 1–50 and similarityThreshold must be finite and between -1 and 1");
        }
        var filters = (requested == null ? RetrievalFilters.none() : requested).withDefaultLanguage(version == null ? null : version.getLanguage());
        filters.documentIds().forEach(id -> documents.getDocument(versionId, id));
        filters.expansionDocumentIds().forEach(id -> {
            if (documents.getDocument(versionId, id).getSourceType() != RuleSourceType.EXPANSION) {
                throw new RetrievalException(RetrievalException.Code.INVALID_SEARCH, "expansionDocumentIds must identify EXPANSION documents owned by the selected version");
            }
        });
        embeddings.requireConfigured();
        var profile = embeddings.profile();
        if (!store.hasEmbeddings(versionId, profile, filters)) {
            return result(versionId, profile, limit, filters, minimum, List.of(), 0, 0, 0, started);
        }
        var vector = embeddings.embed(query.strip());
        var candidates = store.search(versionId, profile, vector, config.candidateLimit(), filters);
        var eligible = candidates.stream().filter(m -> Double.isFinite(m.cosineSimilarity()) && m.cosineSimilarity() >= minimum).toList();
        var unique = duplicates.suppress(eligible, config.duplicateOverlap());
        List<RuleVectorStore.Match> ranked;
        try {
            ranked = unique.isEmpty() ? List.of() : reranker.rerank(query.strip(), unique);
            if (ranked == null || ranked.size() != unique.size() || new HashSet<>(ranked).size() != unique.size()
                    || !new HashSet<>(ranked).equals(new HashSet<>(unique))) { throw new IllegalStateException(); }
        } catch (RuntimeException e) {
            throw new RetrievalException(RetrievalException.Code.RERANKING_FAILED, "Rule reranking failed or returned invalid candidates");
        }
        return result(versionId, profile, limit, filters, minimum, ranked.stream().limit(limit).toList(),
                candidates.size(), candidates.size() - eligible.size(), eligible.size() - unique.size(), started);
    }
    private SearchResult result(UUID version, EmbeddingProfile profile, int topK, RetrievalFilters filters, double threshold,
            List<RuleVectorStore.Match> matches, int count, int below, int duplicateCount, long started) {
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        var diagnostics = new Diagnostics(filters, threshold, config.candidateLimit(), count, below,
                duplicateCount, matches.size(), count == config.candidateLimit(), elapsed);
        LOG.info("Rule retrieval completed: gameVersionId={}, candidates={}, belowThreshold={}, duplicatesRemoved={}, matches={}, elapsedMs={}",
                version, count, below, duplicateCount, matches.size(), elapsed);
        return new SearchResult(version, profile, topK, matches, diagnostics);
    }
}
