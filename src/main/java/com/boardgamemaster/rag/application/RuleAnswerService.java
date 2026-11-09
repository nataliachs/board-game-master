package com.boardgamemaster.rag.application;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.game.application.GameService;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import static com.boardgamemaster.rag.application.RuleAnswer.Confidence.*;

@Service
@EnableConfigurationProperties(AnswerProperties.class)
public class RuleAnswerService {
    public static final String FALLBACK = "I couldn't verify that confidently from the available rules.";
    private static final Logger LOG = LoggerFactory.getLogger(RuleAnswerService.class);
    private final GameService games;
    private final RuleRetrievalService retrieval;
    private final RuleAnswerPromptBuilder prompts;
    private final LlmClient llm;
    private final AnswerProperties config;
    private final EvidenceReviewService review;
    private enum FallbackReason {
        NO_RETRIEVED_EVIDENCE, PROMPT_BUDGET_EXCLUDED_EVIDENCE, NO_STRONG_EVIDENCE,
        MODEL_INSUFFICIENT_EVIDENCE, UNKNOWN_CITATION,
        QUOTE_MISMATCH, WEAK_CITATION, NO_CITATIONS, NO_RELEVANT_EVIDENCE, ANSWER_SUPPORT_REJECTED
    }
    private record Rejection(FallbackReason reason, UUID chunkId) { }

    public RuleAnswerService(GameService games, RuleRetrievalService retrieval, RuleAnswerPromptBuilder prompts,
            LlmClient llm, AnswerProperties config) {
        this(games, retrieval, prompts, llm, config, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RuleAnswerService(GameService games, RuleRetrievalService retrieval, RuleAnswerPromptBuilder prompts,
            LlmClient llm, AnswerProperties config, EvidenceReviewService review) {
        this.games = games; this.retrieval = retrieval; this.prompts = prompts; this.llm = llm; this.config = config;
        this.review = review;
    }

    // Deliberately no transaction: catalog reads finish before any external provider request.
    public RuleAnswer ask(UUID gameId, String question) {
        return ask(gameId, question, null, null, null);
    }

    public RuleAnswer ask(UUID gameId, String question, Integer topK, Double threshold, RetrievalFilters filters) {
        if (question == null || question.isBlank() || question.length() > 4000) {
            throw new RetrievalException(RetrievalException.Code.INVALID_SEARCH, "Question must be nonblank and at most 4000 characters");
        }
        long started = System.nanoTime();
        UUID versionId = games.resolveDefaultVersion(gameId, null).getId();
        return answer(gameId, versionId, question, topK, threshold, filters, null, started);
    }

    public RuleAnswer askPinned(UUID gameId, UUID versionId, String question, AnswerConversation conversation) {
        if (question == null || question.isBlank() || question.length() > 4000) {
            throw new RetrievalException(RetrievalException.Code.INVALID_SEARCH, "Question must be nonblank and at most 4000 characters");
        }
        games.getVersion(gameId, versionId); // Ownership check without resolving a mutable catalog default.
        return answer(gameId, versionId, question, null, null, null, conversation, System.nanoTime());
    }

    private RuleAnswer answer(UUID gameId, UUID versionId, String question, Integer topK, Double threshold,
            RetrievalFilters filters, AnswerConversation conversation, long started) {
        try {
            boolean reviewed = review != null && review.enabled();
            var retrieved = reviewed ? retrieval.searchForReview(versionId, question.strip(), conversation, topK, threshold, filters, review)
                    : conversation != null ? retrieval.searchContextual(versionId, question.strip(), conversation)
                    : topK == null && threshold == null && filters == null
                    ? retrieval.search(versionId, question.strip(), null)
                    : retrieval.search(versionId, question.strip(), topK, threshold, filters);
            var prompt = conversation == null ? prompts.build(versionId, question.strip(), retrieved.matches())
                    : prompts.build(versionId, question.strip(), retrieved.matches(), conversation, retrieved.resolvedQuestion());
            if (reviewed && !prompt.evidence().isEmpty()) {
                var selected = review.select(prompt);
                if (selected.isEmpty()) { return fallback(gameId, versionId, FallbackReason.NO_RELEVANT_EVIDENCE, null); }
                prompt = prompts.build(versionId, question.strip(), selected, conversation, retrieved.resolvedQuestion());
            }
            for (var match : retrieved.matches()) {
                LOG.info("Rule answer evidence: gameId={}, gameVersionId={}, chunkId={}, documentId={}, section={}, page={}, cosineSimilarity={}, includedInPrompt={}, minimumEvidenceSimilarity={}",
                        gameId, versionId, match.chunkId(), match.documentId(), logSection(match.sectionTitle()),
                        match.pageNumber(), match.cosineSimilarity(), prompt.evidence().contains(match),
                        reviewed ? review.candidateFloor() : config.minimumEvidenceSimilarity());
            }
            if (prompt.evidence().isEmpty()) {
                return fallback(gameId, versionId, retrieved.matches().isEmpty() ? FallbackReason.NO_RETRIEVED_EVIDENCE
                        : FallbackReason.PROMPT_BUDGET_EXCLUDED_EVIDENCE, null);
            }
            if (prompt.evidence().stream().noneMatch(m -> strongEnough(m.cosineSimilarity()))) {
                return fallback(gameId, versionId, FallbackReason.NO_STRONG_EVIDENCE, null);
            }
            var generated = generate(gameId, versionId, prompt.request(), 1);
            var rejection = validate(prompt, generated, 1);
            if (rejection != null && rejection.reason() == FallbackReason.QUOTE_MISMATCH) {
                LOG.info("Rule answer correction requested: gameId={}, gameVersionId={}, reason={}, chunkId={}, attempt=2",
                        gameId, versionId, rejection.reason(), rejection.chunkId());
                generated = generate(gameId, versionId, prompts.correctQuote(prompt, rejection.chunkId()), 2);
                rejection = validate(prompt, generated, 2);
                LOG.info("Rule answer correction completed: gameId={}, gameVersionId={}, outcome={}",
                        gameId, versionId, rejection == null ? "ACCEPTED" : rejection.reason());
            }
            if (rejection != null) { return fallback(gameId, versionId, rejection.reason(), rejection.chunkId()); }
            if (reviewed && !review.supports(prompt, generated)) {
                return fallback(gameId, versionId, FallbackReason.ANSWER_SUPPORT_REJECTED, null);
            }
            var sources = new ArrayList<RuleAnswer.Source>();
            var cited = new HashSet<UUID>();
            var seen = new HashSet<List<Object>>();
            for (var citation : generated.citations()) {
                // An exact repeat of a passage and quote is shown once; different quotes from one passage are kept.
                if (!seen.add(List.of(citation.chunkId(), citation.quote().strip()))) { continue; }
                var match = prompt.evidence().stream().filter(m -> m.chunkId().equals(citation.chunkId())).findFirst().orElseThrow();
                cited.add(citation.chunkId());
                sources.add(new RuleAnswer.Source(match.chunkId(), match.documentId(), versionId, match.documentName(),
                        match.sourceType(), match.documentVersion(), match.language(), match.sourceReference(), match.sectionTitle(),
                        match.pageNumber(), match.chunkIndex(), match.startOffset(), match.endOffset(),
                        SourceQuoteMatcher.find(match.content(), citation.quote()).orElseThrow(), match.cosineSimilarity()));
            }
            double weakest = sources.stream().mapToDouble(RuleAnswer.Source::cosineSimilarity).min().orElse(-1);
            if (sources.isEmpty()) { return fallback(gameId, versionId, FallbackReason.NO_CITATIONS, null); }
            // A convenient subset of strong evidence is not sufficient for HIGH confidence.
            boolean covered = prompt.evidence().stream().filter(m -> strongEnough(m.cosineSimilarity()))
                    .allMatch(m -> cited.contains(m.chunkId()));
            boolean meaningfulQuotes = sources.stream().allMatch(s -> s.quote().strip().length() >= config.minimumQuoteCharacters());
            return new RuleAnswer(gameId, versionId, generated.answer().strip(),
                    !reviewed && weakest >= config.highEvidenceSimilarity() && covered && meaningfulQuotes ? HIGH : MEDIUM, sources);
        } finally { LOG.info("Rule question completed: gameId={}, gameVersionId={}, elapsedMs={}",
                gameId, versionId, (System.nanoTime() - started) / 1_000_000); }
    }

    private LlmResponse generate(UUID gameId, UUID versionId, LlmRequest request, int attempt) {
        long started = System.nanoTime();
        try {
            var result = llm.generate(request);
            if (result == null) { throw new LlmException(LlmException.Code.LLM_INVALID_RESPONSE, "LLM provider returned no answer contract"); }
            return result;
        } finally {
            LOG.info("Rule answer LLM call completed: gameId={}, gameVersionId={}, attempt={}, elapsedMs={}",
                    gameId, versionId, attempt, (System.nanoTime() - started) / 1_000_000);
        }
    }
    private Rejection validate(RuleAnswerPromptBuilder.Prompt prompt, LlmResponse generated, int attempt) {
        if (!generated.sufficientEvidence()) { return new Rejection(FallbackReason.MODEL_INSUFFICIENT_EVIDENCE, null); }
        if (generated.citations().isEmpty()) { return new Rejection(FallbackReason.NO_CITATIONS, null); }
        Rejection quoteMismatch = null;
        for (var citation : generated.citations()) {
            var match = prompt.evidence().stream().filter(m -> m.chunkId().equals(citation.chunkId())).findFirst().orElse(null);
            // Other invalid evidence must not trigger a quote-only retry.
            if (match == null) { return new Rejection(FallbackReason.UNKNOWN_CITATION, citation.chunkId()); }
            if (!strongEnough(match.cosineSimilarity())) { return new Rejection(FallbackReason.WEAK_CITATION, citation.chunkId()); }
            if (quoteMismatch == null && SourceQuoteMatcher.find(match.content(), citation.quote()).isEmpty()) {
                QuoteMismatchDiagnostics.log(prompt.evidence(), match, citation, attempt);
                quoteMismatch = new Rejection(FallbackReason.QUOTE_MISMATCH, citation.chunkId());
            }
        }
        return quoteMismatch;
    }

    private boolean strongEnough(double score) {
        double minimum = review != null && review.enabled() ? review.candidateFloor() : config.minimumEvidenceSimilarity();
        return Double.isFinite(score) && score >= minimum && score <= 1;
    }
    private static String logSection(String section) {
        if (section == null) { return ""; }
        String safe = section.replaceAll("\\p{Cntrl}", " ");
        int end = Math.min(160, safe.length());
        if (end > 0 && end < safe.length() && Character.isHighSurrogate(safe.charAt(end - 1))) { end--; }
        return safe.substring(0, end);
    }
    private RuleAnswer fallback(UUID gameId, UUID versionId, FallbackReason reason, UUID chunkId) {
        LOG.info("Rule answer fallback: gameId={}, gameVersionId={}, reason={}, chunkId={}", gameId, versionId, reason, chunkId);
        return new RuleAnswer(gameId, versionId, FALLBACK, LOW, List.of());
    }
}
