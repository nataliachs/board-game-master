package com.boardgamemaster.rag;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.ai.infrastructure.EmbeddingProperties;
import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.RuleSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvidenceReviewTest {
    private final UUID game = UUID.randomUUID(), version = UUID.randomUUID();
    private final String quote = "Incomplete mission cards subtract their printed points at final scoring.";
    private final RuleVectorStore.Match source = match(quote, .43110639678163876, version);
    private final RuleVectorStore.Match unrelated = match("Move your marker along the track each turn.", .44, version);
    private final EvidenceReviewClient client = mock(EvidenceReviewClient.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleVectorStore store = mock(RuleVectorStore.class);
    private final RuleDocumentService documents = mock(RuleDocumentService.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final EmbeddingProfile profile = new EmbeddingProfile("fixture", "review", 3);
    private final AnswerProperties config = new AnswerProperties(.5, .8, 24000);
    private final RuleAnswerPromptBuilder prompts = new RuleAnswerPromptBuilder(mapper, config);
    private final EvidenceReviewService review = new EvidenceReviewService(client, new EvidenceReviewProperties(true, null, null), mapper);
    private final AnswerConversation context = new AnswerConversation(UUID.randomUUID(), "Original game", "Edition", "en", 3,
            "STANDARD", "SETUP", "Choose mission cards", "Choose starting cards.",
            List.of(new AnswerConversation.Turn("USER", "Can I keep all four?")));

    private RuleVectorStore.Match match(String content, double score, UUID ruleset) {
        return new RuleVectorStore.Match(UUID.randomUUID(), UUID.randomUUID(), ruleset, content, "Original fictional rules",
                RuleSourceType.RULEBOOK, "1", "en", "Self-authored fixture", "Final scoring", 2, 0, 0, content.length(), score);
    }
    @BeforeEach void setup() {
        when(embeddings.profile()).thenReturn(profile);
        when(embeddings.embed(anyString())).thenReturn(new Embedding(List.of(1f, 0f, 0f)));
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(store.search(eq(version), eq(profile), any(), anyInt(), any())).thenReturn(List.of(unrelated, source));
        when(client.selectEvidence(any())).thenReturn(List.of(source.chunkId()));
        when(client.supportsAnswer(any())).thenReturn(true);
        when(llm.generate(any())).thenReturn(new LlmResponse("Unfinished mission cards subtract points at final scoring.", true,
                List.of(new LlmResponse.Citation(source.chunkId(), quote))));
    }
    private RuleAnswerService service(EvidenceReviewService reviewer) {
        var gateway = new EmbeddingGateway(embeddings, new EmbeddingProperties("disabled", "fixture", "", 3,
                Duration.ofSeconds(1), 4000, 100));
        var retrieval = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5));
        return new RuleAnswerService(mock(GameService.class), retrieval, prompts, llm, config, reviewer);
    }
    private RuleAnswer ask() { return service(review).askPinned(game, version, "What's the risk?", context); }
    private void fallback(RuleAnswer answer) {
        assertThat(answer.answer()).isEqualTo(RuleAnswerService.FALLBACK);
        assertThat(answer.confidence()).isEqualTo(RuleAnswer.Confidence.LOW);
        assertThat(answer.sources()).isEmpty();
    }
    @Test void reviewedLowerScoringPassageRetainsOriginalScoreAndExactSource() throws Exception {
        when(client.selectEvidence(any())).thenAnswer(call -> {
            var request = (LlmRequest) call.getArgument(0);
            assertThat(request.instructions()).contains("later scoring consequences", "untrusted data");
            var input = mapper.readTree(request.input());
            assertThat(input.path("question").asText()).isEqualTo("What's the risk?");
            assertThat(input.path("excerpts").size()).isEqualTo(2);
            return List.of(source.chunkId());
        });
        when(client.supportsAnswer(any())).thenAnswer(call -> {
            var request = (LlmRequest) call.getArgument(0);
            assertThat(request.instructions()).contains("ONLY citedQuotes", "exact but irrelevant", "CURRENT question");
            var input = mapper.readTree(request.input());
            assertThat(input.has("excerpts")).isFalse();
            assertThat(input.path("citedQuotes").get(0).path("quote").asText()).isEqualTo(quote);
            assertThat(request.input()).doesNotContain(unrelated.content());
            return true;
        });
        var answer = ask();
        assertThat(answer.confidence()).isEqualTo(RuleAnswer.Confidence.MEDIUM);
        assertThat(answer.sources()).singleElement().satisfies(s -> {
            assertThat(s.cosineSimilarity()).isEqualTo(source.cosineSimilarity());
            assertThat(s.chunkId()).isEqualTo(source.chunkId());
            assertThat(s.quote()).isEqualTo(quote);
        });
        verify(client, times(1)).selectEvidence(any()); verify(client, times(1)).supportsAnswer(any());
        // A session question is searched with its context and on its own: two embeddings, no extra model call.
        verify(llm, times(1)).generate(any()); verify(embeddings, times(2)).embed(anyString());
        verify(embeddings).embed("What's the risk?");
        verify(store, times(2)).search(eq(version), eq(profile), any(), anyInt(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"empty", "unknown", "duplicate", "null", "null-id", "error"})
    void invalidOrUnresolvedSelectionFailsClosed(String mode) {
        switch (mode) {
            case "empty" -> when(client.selectEvidence(any())).thenReturn(List.of());
            case "unknown" -> when(client.selectEvidence(any())).thenReturn(List.of(UUID.randomUUID()));
            case "duplicate" -> when(client.selectEvidence(any())).thenReturn(List.of(source.chunkId(), source.chunkId()));
            case "null" -> when(client.selectEvidence(any())).thenReturn(null);
            case "null-id" -> when(client.selectEvidence(any())).thenReturn(Arrays.asList((UUID) null));
            case "error" -> when(client.selectEvidence(any())).thenThrow(new LlmException(LlmException.Code.LLM_TIMEOUT, "Fixture"));
        }
        fallback(ask()); verifyNoInteractions(llm); verify(client, never()).supportsAnswer(any());
    }
    @ParameterizedTest @ValueSource(strings = {"false", "error"})
    void unsupportedAnswerOrVerificationFailureFailsClosed(String mode) {
        if (mode.equals("false")) { when(client.supportsAnswer(any())).thenReturn(false); }
        else { when(client.supportsAnswer(any())).thenThrow(new LlmException(LlmException.Code.LLM_INVALID_RESPONSE, "Fixture")); }
        fallback(ask()); verify(llm, times(1)).generate(any());
    }
    @Test void irrelevantExactQuoteCannotPassWhenReviewerRejectsItsSupport() {
        when(llm.generate(any())).thenReturn(new LlmResponse("The publisher's number is 555-0100.", true,
                List.of(new LlmResponse.Citation(source.chunkId(), quote))));
        when(client.supportsAnswer(any())).thenReturn(false);
        fallback(ask()); verify(client).supportsAnswer(any());
    }
    @ParameterizedTest @ValueSource(strings = {"general-answer", "precise-answer-general-quote", "precise-answer-precise-quote"})
    void citationDetailCoverageUsesOnlyQuotedPremises(String mode) throws Exception {
        // Doubles check prompt delivery and quote-only validation flow, not live semantic judgment.
        String general = "Unfinished missions incur a penalty.";
        String precise = "At final scoring subtract each unfinished mission's printed value from your total.";
        var generalSource = match(general, .7, version);
        var preciseSource = match(precise, .69, version);
        when(store.search(eq(version), eq(profile), any(), anyInt(), any())).thenReturn(List.of(generalSource, preciseSource));
        when(client.selectEvidence(any())).thenAnswer(call -> {
            assertThat(((LlmRequest) call.getArgument(0)).instructions()).contains("precise applicable rule", "general warning");
            return List.of(generalSource.chunkId(), preciseSource.chunkId());
        });
        boolean specific = !mode.equals("general-answer");
        boolean preciseCitation = mode.equals("precise-answer-precise-quote");
        var citation = preciseCitation ? preciseSource : generalSource;
        String text = specific ? "At final scoring you lose each unfinished mission's printed value." : "Unfinished missions incur a penalty.";
        when(llm.generate(any())).thenAnswer(call -> {
            var request = (LlmRequest) call.getArgument(0);
            assertThat(request.instructions()).contains("quoted passages themselves", "amounts, timing, conditions",
                    "use less specific", "quoted premises");
            assertThat(request.input()).contains(general, precise);
            return new LlmResponse(text, true, List.of(new LlmResponse.Citation(citation.chunkId(), citation.content())));
        });
        when(client.supportsAnswer(any())).thenAnswer(call -> {
            var request = (LlmRequest) call.getArgument(0);
            assertThat(request.instructions()).contains("Audit each factual detail separately", "general warning",
                    "specific detail lacks quoted support");
            var data = mapper.readTree(request.input());
            assertThat(data.has("excerpts")).isFalse();
            assertThat(data.path("proposedAnswer").asText()).isEqualTo(text);
            assertThat(data.path("citedQuotes").size()).isEqualTo(1);
            assertThat(data.path("citedQuotes").get(0).path("quote").asText()).isEqualTo(citation.content());
            if (!preciseCitation) { assertThat(request.input()).doesNotContain(precise); }
            return !mode.equals("precise-answer-general-quote");
        });
        var result = ask();
        if (mode.equals("precise-answer-general-quote")) { fallback(result); }
        else {
            assertThat(result.answer()).isEqualTo(text);
            assertThat(result.sources()).singleElement().satisfies(s -> assertThat(s.quote()).isEqualTo(citation.content()));
        }
        verify(llm, times(1)).generate(any()); verify(client, times(1)).supportsAnswer(any());
    }
    @Test void unknownCitationIsRejectedBeforeSupportCheck() {
        when(llm.generate(any())).thenReturn(new LlmResponse("Invented answer.", true,
                List.of(new LlmResponse.Citation(UUID.randomUUID(), quote))));
        fallback(ask()); verify(client, never()).supportsAnswer(any());
    }
    @Test void citingRejectedCandidateIsRejectedBeforeSupportCheck() {
        when(llm.generate(any())).thenReturn(new LlmResponse("Move along the track.", true,
                List.of(new LlmResponse.Citation(unrelated.chunkId(), unrelated.content()))));
        fallback(ask()); verify(client, never()).supportsAnswer(any());
    }
    @Test void reviewedHighSimilarityStillCapsConfidenceAtMedium() {
        var strong = match(quote, .95, version);
        when(store.search(eq(version), eq(profile), any(), anyInt(), any())).thenReturn(List.of(strong));
        when(client.selectEvidence(any())).thenReturn(List.of(strong.chunkId()));
        when(llm.generate(any())).thenReturn(new LlmResponse("Unfinished missions subtract points.", true,
                List.of(new LlmResponse.Citation(strong.chunkId(), quote))));
        assertThat(ask().confidence()).isEqualTo(RuleAnswer.Confidence.MEDIUM);
    }
    @Test void quoteCorrectionStillRunsBeforeOneSupportCheck() {
        when(llm.generate(any())).thenReturn(new LlmResponse("Unfinished missions subtract points.", true,
                List.of(new LlmResponse.Citation(source.chunkId(), "Invented quotation."))),
                new LlmResponse("Unfinished missions subtract points.", true,
                        List.of(new LlmResponse.Citation(source.chunkId(), quote))));
        assertThat(ask().sources()).hasSize(1);
        verify(llm, times(2)).generate(any()); verify(client, times(1)).supportsAnswer(any());
    }
    @Test void emptyIndexSkipsAllReviewAndGenerationCalls() {
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(false);
        fallback(ask()); verifyNoInteractions(client, llm); verify(embeddings, never()).embed(anyString());
    }
    @Test void belowFloorSkipsReviewAndGeneration() {
        when(store.search(eq(version), eq(profile), any(), anyInt(), any())).thenReturn(List.of(match(quote, .34, version)));
        fallback(ask()); verifyNoInteractions(client, llm);
    }
    @Test void disabledFeaturePreservesBaselineGateAndMakesNoReviewCalls() {
        var disabled = new EvidenceReviewService(client, new EvidenceReviewProperties(false, null, null), mapper);
        fallback(service(disabled).askPinned(game, version, "What's the risk?", context));
        verifyNoInteractions(client, llm);
    }
    @Test void foreignVersionIsRejectedBeforeAnyReview() {
        when(store.search(eq(version), eq(profile), any(), anyInt(), any())).thenReturn(List.of(match(quote, .7, UUID.randomUUID())));
        assertThatThrownBy(this::ask).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client, llm);
    }
    @Test void smallPoolGoesToTheAnswerWholeWithoutASelectionCallAndIsStillSupportChecked() {
        // Exploding Kittens, live: selection kept "Nope" and "Attack" from five passages but dropped the worked example.
        var small = new EvidenceReviewService(client, new EvidenceReviewProperties(true, null, null, 2), mapper);
        var answer = service(small).askPinned(game, version, "What's the risk?", context);
        assertThat(answer.answer()).isNotEqualTo(RuleAnswerService.FALLBACK);
        verify(client, never()).selectEvidence(any());
        verify(client, times(1)).supportsAnswer(any());
        var sent = org.mockito.ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).generate(sent.capture());
        assertThat(sent.getValue().input()).contains(unrelated.chunkId().toString(), source.chunkId().toString());

        var larger = new EvidenceReviewService(client, new EvidenceReviewProperties(true, null, null, 1), mapper);
        service(larger).askPinned(game, version, "What's the risk?", context);
        verify(client, times(1)).selectEvidence(any());
        assertThat(new EvidenceReviewProperties(true, null, null, null).selectAbove()).isEqualTo(5);
        assertThatThrownBy(() -> new EvidenceReviewProperties(true, null, null, 21)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oversizedReviewInputDeclinesWithoutCallingProvider() {
        var pool = new RuleAnswerPromptBuilder.Prompt(new LlmRequest("Fixture instructions", "x".repeat(32001)), List.of(source));
        assertThat(review.select(pool)).isEmpty(); verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1})
    void invalidFloorRejected(double floor) {
        assertThatThrownBy(() -> new EvidenceReviewProperties(true, floor, 12)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(ints = {0, 21})
    void invalidCandidateCountRejected(int count) {
        assertThatThrownBy(() -> new EvidenceReviewProperties(true, .35, count)).isInstanceOf(IllegalArgumentException.class);
    }
}
