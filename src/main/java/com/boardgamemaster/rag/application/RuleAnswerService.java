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

    public RuleAnswerService(GameService games, RuleRetrievalService retrieval, RuleAnswerPromptBuilder prompts,
            LlmClient llm, AnswerProperties config) {
        this.games = games; this.retrieval = retrieval; this.prompts = prompts; this.llm = llm; this.config = config;
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
            var retrieved = conversation != null ? retrieval.searchContextual(versionId, question.strip(), conversation)
                    : topK == null && threshold == null && filters == null
                    ? retrieval.search(versionId, question.strip(), null)
                    : retrieval.search(versionId, question.strip(), topK, threshold, filters);
            var prompt = conversation == null ? prompts.build(versionId, question.strip(), retrieved.matches())
                    : prompts.build(versionId, question.strip(), retrieved.matches(), conversation);
            if (prompt.evidence().stream().noneMatch(m -> strongEnough(m.cosineSimilarity()))) { return fallback(gameId, versionId); }
            long llmStarted = System.nanoTime();
            LlmResponse generated;
            try { generated = llm.generate(prompt.request()); }
            finally { LOG.info("Rule answer LLM call completed: gameId={}, gameVersionId={}, elapsedMs={}",
                    gameId, versionId, (System.nanoTime() - llmStarted) / 1_000_000); }
            if (generated == null) { throw new LlmException(LlmException.Code.LLM_INVALID_RESPONSE, "LLM provider returned no answer contract"); }
            if (!generated.sufficientEvidence()) { return fallback(gameId, versionId); }
            var sources = new ArrayList<RuleAnswer.Source>();
            var cited = new HashSet<UUID>();
            for (var citation : generated.citations()) {
                var match = prompt.evidence().stream().filter(m -> m.chunkId().equals(citation.chunkId())).findFirst().orElse(null);
                // Fail closed on invented sources/quotes, duplicate citations or weak cited evidence.
                if (match == null || !cited.add(citation.chunkId()) || !match.content().contains(citation.quote())
                        || !strongEnough(match.cosineSimilarity())) { return fallback(gameId, versionId); }
                sources.add(new RuleAnswer.Source(match.chunkId(), match.documentId(), versionId, match.documentName(),
                        match.sourceType(), match.documentVersion(), match.language(), match.sourceReference(), match.sectionTitle(),
                        match.pageNumber(), match.chunkIndex(), match.startOffset(), match.endOffset(), citation.quote(), match.cosineSimilarity()));
            }
            double weakest = sources.stream().mapToDouble(RuleAnswer.Source::cosineSimilarity).min().orElse(-1);
            if (sources.isEmpty()) { return fallback(gameId, versionId); }
            // A convenient subset of strong evidence is not sufficient for HIGH confidence.
            boolean covered = prompt.evidence().stream().filter(m -> strongEnough(m.cosineSimilarity()))
                    .allMatch(m -> cited.contains(m.chunkId()));
            boolean meaningfulQuotes = generated.citations().stream().allMatch(c -> c.quote().strip().length() >= config.minimumQuoteCharacters());
            return new RuleAnswer(gameId, versionId, generated.answer().strip(),
                    weakest >= config.highEvidenceSimilarity() && covered && meaningfulQuotes ? HIGH : MEDIUM, sources);
        } finally { LOG.info("Rule question completed: gameId={}, gameVersionId={}, elapsedMs={}",
                gameId, versionId, (System.nanoTime() - started) / 1_000_000); }
    }

    private boolean strongEnough(double score) { return Double.isFinite(score) && score >= config.minimumEvidenceSimilarity() && score <= 1; }
    private RuleAnswer fallback(UUID gameId, UUID versionId) { return new RuleAnswer(gameId, versionId, FALLBACK, LOW, List.of()); }
}
