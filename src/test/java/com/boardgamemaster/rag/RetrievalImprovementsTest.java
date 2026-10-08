package com.boardgamemaster.rag;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.ai.infrastructure.EmbeddingProperties;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.RuleSourceType;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalImprovementsTest {
    private final UUID version = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final EmbeddingProfile profile = new EmbeddingProfile("fixture", "known", 3);
    private final RuleDocumentService documents = mock(RuleDocumentService.class);
    private final RuleVectorStore store = mock(RuleVectorStore.class);
    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final EmbeddingGateway gateway = new EmbeddingGateway(client,
            new EmbeddingProperties("disabled", "test", "", 3, Duration.ofSeconds(1), 4000, 100));
    private final RuleDuplicateSuppressor duplicates = new RuleDuplicateSuppressor();
    private final RuleReranker reranker = mock(RuleReranker.class);
    private final RuleRetrievalService retrieval = new RuleRetrievalService(documents, store, gateway,
            new RetrievalProperties(2), duplicates, reranker);

    @BeforeEach void setup() {
        when(client.profile()).thenReturn(profile);
        when(client.embed(anyString())).thenReturn(new Embedding(List.of(1f, 0f, 0f)));
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(reranker.rerank(anyString(), anyList())).thenAnswer(c -> c.getArgument(1));
    }
    private RuleVectorStore.Match match(UUID document, String content, RuleSourceType type, double score) {
        return new RuleVectorStore.Match(UUID.randomUUID(), document, version, content, "Original fixture", type,
                "1", "en", "original", "Section", null, 0, 0, content.length(), score);
    }
    @Test void deduplicatesBeforeTopKAndRecordsAllStages() {
        var first = match(document, "Move one space.", RuleSourceType.RULEBOOK, 1);
        var duplicate = match(UUID.randomUUID(), "Move  one\nspace.", RuleSourceType.RULEBOOK, .99);
        var second = match(document, "Take two markers.", RuleSourceType.RULEBOOK, .8);
        var below = match(document, "Unrelated.", RuleSourceType.RULEBOOK, .49);
        when(store.search(eq(version), eq(profile), any(), eq(200), any())).thenReturn(List.of(first, duplicate, second, below));
        var result = retrieval.search(version, "query", null);
        assertThat(result.matches()).containsExactly(first, second);
        assertThat(result.diagnostics().candidates()).isEqualTo(4);
        assertThat(result.diagnostics().belowThreshold()).isEqualTo(1);
        assertThat(result.diagnostics().duplicatesRemoved()).isEqualTo(1);
        assertThat(result.diagnostics().returned()).isEqualTo(2);
        assertThat(result.diagnostics().candidateLimitReached()).isFalse();
    }
    @Test void acceptsRerankerPermutationBeforeTopKAndPreservesOriginalScores() {
        var first = match(document, "First rule.", RuleSourceType.FAQ, .9);
        var second = match(document, "Second rule.", RuleSourceType.FAQ, .8);
        when(store.search(eq(version), eq(profile), any(), eq(200), any())).thenReturn(List.of(first, second));
        doReturn(List.of(second, first)).when(reranker).rerank(anyString(), anyList());
        assertThat(retrieval.search(version, "query", 1).matches()).containsExactly(second);
        assertThat(second.cosineSimilarity()).isEqualTo(.8);
    }
    @ParameterizedTest @ValueSource(strings = {"null", "missing", "duplicate", "foreign", "throws"})
    void invalidRerankingFailsSafely(String mode) {
        var first = match(document, "First rule.", RuleSourceType.FAQ, .9);
        var second = match(document, "Second rule.", RuleSourceType.FAQ, .8);
        when(store.search(eq(version), eq(profile), any(), eq(200), any())).thenReturn(List.of(first, second));
        if (mode.equals("throws")) { doThrow(new RuntimeException("Secret upstream body")).when(reranker).rerank(anyString(), anyList()); }
        else {
            List<RuleVectorStore.Match> invalid = switch (mode) {
                case "null" -> null;
                case "missing" -> List.of(first);
                case "duplicate" -> List.of(first, first);
                default -> List.of(first, match(UUID.randomUUID(), "Forged", RuleSourceType.FAQ, 1));
            };
            doReturn(invalid).when(reranker).rerank(anyString(), anyList());
        }
        assertThatThrownBy(() -> retrieval.search(version, "query", null)).isInstanceOfSatisfying(RetrievalException.class, e -> {
            assertThat(e.code()).isEqualTo(RetrievalException.Code.RERANKING_FAILED);
            assertThat(e.getMessage()).doesNotContain("Secret"); assertThat(e.getCause()).isNull();
        });
    }
    @ParameterizedTest @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -1.01, 1.01})
    void invalidThresholdNeverCallsProviders(double threshold) {
        clearInvocations(client, store);
        assertThatThrownBy(() -> retrieval.search(version, "query", null, threshold, null)).isInstanceOf(RetrievalException.class);
        verify(client, never()).embed(anyString()); verifyNoInteractions(store);
    }
    @Test void duplicateSuppressionPreservesConflictingAndDifferentSourceRules() {
        var first = match(document, "Move one space.", RuleSourceType.RULEBOOK, 1);
        var negated = match(document, "Do not move one space.", RuleSourceType.RULEBOOK, .9);
        var changed = match(document, "Move two spaces.", RuleSourceType.RULEBOOK, .9);
        var faq = match(document, "Move one space.", RuleSourceType.FAQ, .9);
        assertThat(duplicates.suppress(List.of(first, negated, changed, faq), .85)).containsExactly(first, negated, changed, faq);
    }
    @Test void substantialContainedOverlapIsSuppressedButNewConflictingWordsSurvive() {
        String text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty";
        var first = match(document, text, RuleSourceType.RULEBOOK, 1);
        var contained = match(document, text.substring(4), RuleSourceType.RULEBOOK, .9);
        var changed = match(document, text.replace("ten", "not-ten"), RuleSourceType.RULEBOOK, .8);
        assertThat(duplicates.suppress(List.of(first, contained, changed), .85)).containsExactly(first, changed);
        assertThat(duplicates.suppress(List.of(contained, first), .85)).containsExactly(contained, first);
        assertThat(duplicates.suppress(List.of(first, contained), 1)).containsExactly(first, contained);
    }
    @Test void saturatedCandidatePoolIsReportedRatherThanClaimingExhaustiveRecall() {
        var candidates = new ArrayList<RuleVectorStore.Match>();
        for (int i = 0; i < 50; i++) { candidates.add(match(document, "Same repeated source.", RuleSourceType.FAQ, 1)); }
        when(store.search(eq(version), eq(profile), any(), eq(50), any())).thenReturn(candidates);
        var bounded = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5, .5, 50, .85), duplicates, reranker);
        var found = bounded.search(version, "query", null);
        assertThat(found.matches()).hasSize(1);
        assertThat(found.diagnostics().candidateLimitReached()).isTrue();
        assertThat(found.diagnostics().duplicatesRemoved()).isEqualTo(49);
    }
    @Test void filtersAreBoundedNormalizedAndImmutable() {
        var ids = new HashSet<>(Set.of(document));
        var filter = new RetrievalFilters(" EN-us ", Set.of(RuleSourceType.FAQ), ids, null);
        ids.clear();
        assertThat(filter.language()).isEqualTo("en-us"); assertThat(filter.documentIds()).containsExactly(document);
        assertThatThrownBy(() -> filter.documentIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RetrievalFilters("not a language", null, null, null)).isInstanceOf(RetrievalException.class);
        var tooMany = new HashSet<UUID>(); for (int i = 0; i < 51; i++) { tooMany.add(UUID.randomUUID()); }
        assertThatThrownBy(() -> new RetrievalFilters(null, null, tooMany, null)).isInstanceOf(RetrievalException.class);
    }
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(RetrievalProperties.class)
    static class Config { }
    @ParameterizedTest @ValueSource(strings = {"boardgame.rag.similarity-threshold=NaN", "boardgame.rag.similarity-threshold=1.1",
            "boardgame.rag.candidate-limit=49", "boardgame.rag.candidate-limit=501", "boardgame.rag.duplicate-overlap=0.1"})
    void invalidConfigurationFailsStartup(String property) {
        new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(property).run(c -> assertThat(c).hasFailed());
    }
    @Test void blankLegacyThresholdRetainsSafeDefault() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues("boardgame.rag.similarity-threshold=")
                .run(c -> { assertThat(c).hasNotFailed(); assertThat(c.getBean(RetrievalProperties.class).similarityThreshold()).isEqualTo(.5); });
    }
}
