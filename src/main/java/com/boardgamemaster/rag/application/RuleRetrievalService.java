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
    private final ContextualQueryRewriter queryRewriter;

    public RuleRetrievalService(RuleDocumentService documents, RuleVectorStore store, EmbeddingGateway embeddings, RetrievalProperties config) {
        this(documents, store, embeddings, config, new RuleDuplicateSuppressor(), (query, candidates) -> candidates);
    }
    public RuleRetrievalService(RuleDocumentService documents, RuleVectorStore store, EmbeddingGateway embeddings,
            RetrievalProperties config, RuleDuplicateSuppressor duplicates, RuleReranker reranker) {
        this(documents, store, embeddings, config, duplicates, reranker, null);
    }
    @Autowired
    public RuleRetrievalService(RuleDocumentService documents, RuleVectorStore store, EmbeddingGateway embeddings,
            RetrievalProperties config, RuleDuplicateSuppressor duplicates, RuleReranker reranker,
            ContextualQueryRewriter queryRewriter) {
        this.documents = documents; this.store = store; this.embeddings = embeddings; this.config = config;
        this.duplicates = duplicates; this.reranker = reranker;
        this.queryRewriter = queryRewriter;
    }
    public record Diagnostics(RetrievalFilters filters, double similarityThreshold, int candidateLimit, int candidates,
            int belowThreshold, int duplicatesRemoved, int returned, boolean candidateLimitReached, long elapsedMs) { }
    /** {@code resolvedQuestion} is the standalone restatement of a session follow-up, when one was made; never evidence. */
    public record SearchResult(UUID gameVersionId, EmbeddingProfile profile, int topK, List<RuleVectorStore.Match> matches,
            Diagnostics diagnostics, String resolvedQuestion) {
        public SearchResult { matches = List.copyOf(matches); }
        public SearchResult(UUID versionId, EmbeddingProfile profile, int topK, List<RuleVectorStore.Match> matches, Diagnostics diagnostics) {
            this(versionId, profile, topK, matches, diagnostics, null);
        }
        public SearchResult(UUID versionId, EmbeddingProfile profile, int topK, List<RuleVectorStore.Match> matches) {
            this(versionId, profile, topK, matches, null, null);
        }
    }
    /** Game context for the one empty-search retry of an answer search; null disables the retry. */
    public record Expansion(String game, String edition) { }

    public SearchResult search(UUID versionId, String query, Integer topK) { return search(versionId, query, topK, null, null); }

    /** Answer search for a standalone question: like {@link #search} with defaults, plus the empty-search retry. */
    public SearchResult searchQuestion(UUID versionId, String question, Expansion expansion) {
        return searchInternal(versionId, question, null, null, null, question, null, expansion);
    }

    public SearchResult searchContextual(UUID versionId, String question, AnswerConversation conversation) {
        embeddings.validateText(question);
        String query = conversation.retrievalQuery(question, Math.min(4000, embeddings.maxInputCharacters()));
        return searchInternal(versionId, query, null, null, null, question, conversation, expansionOf(conversation));
    }

    public SearchResult search(UUID versionId, String query, Integer topK, Double threshold, RetrievalFilters requested) {
        return searchInternal(versionId, query, topK, threshold, requested, null, null, null);
    }
    private static Expansion expansionOf(AnswerConversation conversation) {
        return conversation == null ? new Expansion(null, null) : new Expansion(conversation.game(), conversation.edition());
    }

    /** Answer-only candidate pool; ordinary search retains its configured threshold. */
    public SearchResult searchForReview(UUID versionId, String question, AnswerConversation conversation,
            Integer topK, Double threshold, RetrievalFilters filters, EvidenceReviewService review) {
        embeddings.validateText(question);
        String query = conversation == null ? question : conversation.retrievalQuery(question, Math.min(4000, embeddings.maxInputCharacters()));
        // Explicit API overrides may restrict the experimental pool, never expand its configured bounds.
        int count = topK == null ? review.candidateCount() : Math.min(topK, review.candidateCount());
        double floor = threshold == null ? review.candidateFloor() : Math.max(threshold, review.candidateFloor());
        if (topK != null && (topK < 1 || topK > 50) || threshold != null
                && (!Double.isFinite(threshold) || threshold < -1 || threshold > 1)) {
            throw new RetrievalException(RetrievalException.Code.INVALID_SEARCH, "Invalid evidence review search bounds");
        }
        return searchInternal(versionId, query, count, floor, filters, question, conversation, expansionOf(conversation));
    }

    private SearchResult searchInternal(UUID versionId, String query, Integer topK, Double threshold,
            RetrievalFilters requested, String originalQuestion, AnswerConversation conversation, Expansion expansion) {
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
        String resolved = null;
        if (conversation != null && queryRewriter != null) {
            resolved = queryRewriter.rewrite(originalQuestion, conversation, Math.min(4000, embeddings.maxInputCharacters())).orElse(null);
            if (resolved != null) { query = resolved; }
        }
        List<RuleVectorStore.Match> candidates;
        // A session question that no rewrite made standalone has the previous question and lesson/setup text
        // attached. That helps a vague question but buries a clear one: live, a welcome lesson's text lifted
        // "Overview", "Setup" and eight other general passages above "Attack" for "A attacks, B attacks, C Nopes",
        // so the Attack rule missed the answer's evidence. The question is therefore searched alone first. When
        // that finds a passage at the regular threshold, the question is clear and only its own scores count;
        // otherwise the padded query is searched too and each passage keeps its higher score.
        if (conversation != null && resolved == null && originalQuestion != null && !originalQuestion.isBlank()
                && !originalQuestion.strip().equals(query.strip())) {
            candidates = store.search(versionId, profile, embeddings.embed(originalQuestion.strip()), config.candidateLimit(), filters);
            RetrievalQueryDiagnostics.log(versionId, originalQuestion.strip(), candidates, minimum);
            if (eligible(candidates, Math.max(minimum, config.similarityThreshold())).isEmpty()) {
                var padded = store.search(versionId, profile, embeddings.embed(query.strip()), config.candidateLimit(), filters);
                RetrievalQueryDiagnostics.log(versionId, query.strip(), padded, minimum);
                candidates = higherScores(padded, candidates);
            } else {
                query = originalQuestion;
            }
        } else {
            candidates = store.search(versionId, profile, embeddings.embed(query.strip()), config.candidateLimit(), filters);
            RetrievalQueryDiagnostics.log(versionId, query.strip(), candidates, minimum);
        }
        var eligible = eligible(candidates, minimum);
        // A short or casual question ("How do I win?") can rank the right passages first yet score below the
        // threshold. Answer searches retry once with the question in rulebook wording; the threshold is unchanged.
        if (eligible.isEmpty() && !candidates.isEmpty() && expansion != null && resolved == null && queryRewriter != null) {
            var expanded = queryRewriter.expand(originalQuestion, expansion.game(), expansion.edition(),
                    Math.min(4000, embeddings.maxInputCharacters()));
            if (expanded.isPresent()) {
                query = expanded.get();
                candidates = store.search(versionId, profile, embeddings.embed(query.strip()), config.candidateLimit(), filters);
                RetrievalQueryDiagnostics.log(versionId, query.strip(), candidates, minimum);
                eligible = eligible(candidates, minimum);
                LOG.info("Empty-search retry completed: gameVersionId={}, matches={}", versionId, eligible.size());
            }
        }
        var unique = duplicates.suppress(eligible, config.duplicateOverlap());
        List<RuleVectorStore.Match> ranked;
        try {
            ranked = unique.isEmpty() ? List.of() : reranker.rerank(query.strip(), unique);
            if (ranked == null || ranked.size() != unique.size() || new HashSet<>(ranked).size() != unique.size()
                    || !new HashSet<>(ranked).equals(new HashSet<>(unique))) { throw new IllegalStateException(); }
        } catch (RuntimeException e) {
            throw new RetrievalException(RetrievalException.Code.RERANKING_FAILED, "Rule reranking failed or returned invalid candidates");
        }
        var found = result(versionId, profile, limit, filters, minimum, ranked.stream().limit(limit).toList(),
                candidates.size(), candidates.size() - eligible.size(), eligible.size() - unique.size(), started);
        return new SearchResult(found.gameVersionId(), found.profile(), found.topK(), found.matches(), found.diagnostics(), resolved);
    }
    /** Each passage once, with the better of its two scores, best first; scores are never raised beyond either search. */
    static List<RuleVectorStore.Match> higherScores(List<RuleVectorStore.Match> first, List<RuleVectorStore.Match> second) {
        var best = new LinkedHashMap<UUID, RuleVectorStore.Match>();
        for (var match : first) { best.merge(match.chunkId(), match, RuleRetrievalService::better); }
        for (var match : second) { best.merge(match.chunkId(), match, RuleRetrievalService::better); }
        var merged = new ArrayList<>(best.values());
        merged.sort((a, b) -> Double.compare(score(b), score(a)));
        return List.copyOf(merged);
    }
    private static RuleVectorStore.Match better(RuleVectorStore.Match a, RuleVectorStore.Match b) {
        return score(b) > score(a) ? b : a;
    }
    private static double score(RuleVectorStore.Match match) {
        return Double.isFinite(match.cosineSimilarity()) ? match.cosineSimilarity() : -2;
    }
    private static List<RuleVectorStore.Match> eligible(List<RuleVectorStore.Match> candidates, double minimum) {
        return candidates.stream().filter(m -> Double.isFinite(m.cosineSimilarity()) && m.cosineSimilarity() >= minimum).toList();
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
