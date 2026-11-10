package com.boardgamemaster.rag;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.ai.infrastructure.EmbeddingProperties;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.application.RuleDocumentService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleRetrievalServiceTest {
    @Test void enabledRewriteFeedsOneSearchAndEmptyIndexSkipsRewriteProvider() {
        var rewriteClient = mock(QueryRewriteClient.class);
        var rewriter = new ContextualQueryRewriter(rewriteClient, new com.fasterxml.jackson.databind.ObjectMapper(),
                new QueryRewriteProperties(true));
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5),
                new RuleDuplicateSuppressor(), (query, candidates) -> candidates, rewriter);
        var context = new AnswerConversation(UUID.randomUUID(), "Original game", "Edition", "en", 3, "STANDARD", "SETUP",
                "Choose missions", "Choose starting mission cards.",
                List.of(new AnswerConversation.Turn("USER", "Can I keep all four mission cards?")));
        when(client.profile()).thenReturn(profile);
        service.searchContextual(version, "What's the risk?", context);
        verifyNoInteractions(rewriteClient);
        verify(client, never()).embed(anyString());

        String standalone = "What are the consequences of keeping all four starting mission cards?";
        var vector = new Embedding(List.of(1f, 0f, 0f));
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(rewriteClient.rewrite(any())).thenReturn(java.util.Optional.of(standalone));
        when(client.embed(standalone)).thenReturn(vector);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of());
        var result = service.searchContextual(version, "What's the risk?", context);
        verify(rewriteClient, times(1)).rewrite(any());
        verify(client, times(1)).embed(standalone);
        verify(store, times(1)).search(eq(version), eq(profile), eq(vector), anyInt(), any());
        assertThat(result.resolvedQuestion()).as("the answer step must see what the follow-up means").isEqualTo(standalone);
    }

    @Test void rewriteFailureRetainsDeterministicQueryAndStatelessSearchSkipsRewrite() {
        var rewriteClient = mock(QueryRewriteClient.class);
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5),
                new RuleDuplicateSuppressor(), (query, candidates) -> candidates,
                new ContextualQueryRewriter(rewriteClient, new com.fasterxml.jackson.databind.ObjectMapper(), new QueryRewriteProperties(true)));
        var context = new AnswerConversation(UUID.randomUUID(), "Original game", "Edition", "en", 3, "STANDARD", "SETUP",
                "Choose missions", "", List.of(new AnswerConversation.Turn("USER", "Can I keep four?")));
        when(client.profile()).thenReturn(profile);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        var vector = new Embedding(List.of(1f, 0f, 0f));
        when(client.embed(anyString())).thenReturn(vector);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of());
        when(rewriteClient.rewrite(any())).thenThrow(new LlmException(LlmException.Code.LLM_TIMEOUT, "safe error"));
        assertThat(service.searchContextual(version, "What's the risk?", context).resolvedQuestion()).isNull();
        verify(client).embed(context.retrievalQuery("What's the risk?", 100));
        service.search(version, "standalone", null);
        verify(rewriteClient, times(1)).rewrite(any());
        verify(client).embed("standalone");
    }
    @Test void contextualFollowUpIsSearchedWithPriorTopicWithinSmallInputLimitAndOnItsOwn() {
        var context = new AnswerConversation(UUID.randomUUID(), "Original game", "Edition", "en", 3, "STANDARD", "SETUP",
                "Prepare missions", "Long lesson explanation. ".repeat(40),
                List.of(new AnswerConversation.Turn("USER", "Can I keep all four mission cards?"),
                        new AnswerConversation.Turn("ASSISTANT", "Invented answer must not enter retrieval.")),
                new AnswerConversation.SetupContext("MISSIONS", "Choose mission cards", "Long dealing procedure. ".repeat(40)));
        String question = "What's the risk?";
        var vector = new Embedding(List.of(1f, 0f, 0f));
        var queries = new java.util.ArrayList<String>();
        when(client.profile()).thenReturn(profile);
        when(client.embed(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            queries.add(query);
            assertThat(query).startsWith(question).doesNotContain("Invented answer").hasSizeLessThanOrEqualTo(100);
            return vector;
        });
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of());
        var result = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5))
                .searchContextual(version, question, context);
        assertThat(result.gameVersionId()).isEqualTo(version);
        assertThat(result.matches()).isEmpty();
        assertThat(queries).hasSize(2);
        assertThat(queries.get(0)).contains("Can I keep all four mission cards?");
        assertThat(queries.get(1)).isEqualTo(question);
        verify(store, times(2)).search(eq(version), eq(profile), eq(vector), anyInt(), any());
    }

    @Test void sessionQuestionKeepsEachPassagesBetterScoreSoContextCannotBuryAClearQuestion() {
        // Exploding Kittens, live: the lesson text attached to "A attacks, B attacks, C Nopes" ranked
        // "Overview" level with "Nope" and left "Attack" under the threshold.
        var context = new AnswerConversation(UUID.randomUUID(), "Exploding Kittens", "Original", "en", 4, "STANDARD", "TEACHING",
                "Welcome to Exploding Kittens", "Exploding Kittens is a card game about avoiding exploding kittens.", List.of());
        String question = "What happens when A plays an Attack, B plays an Attack and C plays a Nope?";
        var padded = new Embedding(List.of(1f, 0f, 0f));
        var alone = new Embedding(List.of(0f, 1f, 0f));
        when(client.profile()).thenReturn(profile);
        when(client.embed(anyString())).thenAnswer(invocation -> question.equals(invocation.getArgument(0)) ? alone : padded);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        var overviewPadded = scored("Overview of the game", .64);
        var attackPadded = scored("Attack", .48);
        var attackAlone = new RuleVectorStore.Match(attackPadded.chunkId(), document, version, "Attack text.", "Rules reference",
                com.boardgamemaster.rules.domain.RuleSourceType.RULEBOOK, "1", "en", "Original fixture", "Attack", null, 0, 0, 10, .66);
        var nope = scored("Nope", .71);
        when(store.search(eq(version), eq(profile), eq(padded), anyInt(), any())).thenReturn(List.of(overviewPadded, attackPadded));
        when(store.search(eq(version), eq(profile), eq(alone), anyInt(), any())).thenReturn(List.of(nope, attackAlone, scored("Setup", .3)));

        var result = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5))
                .searchContextual(version, question, context);
        assertThat(result.matches()).extracting(RuleVectorStore.Match::sectionTitle).containsExactly("Nope", "Attack", "Overview of the game");
        assertThat(result.matches().get(1).cosineSimilarity()).as("each passage keeps its better real score").isEqualTo(.66);
    }

    @Test void plainSearchIsSearchedOnce() {
        var vector = new Embedding(List.of(1f, 0f, 0f));
        when(client.profile()).thenReturn(profile);
        when(client.embed(anyString())).thenReturn(vector);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of(scored("Setup", .7)));
        new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5)).search(version, "How do I set up?", null);
        verify(client, times(1)).embed(anyString());
    }
    private RuleVectorStore.Match scored(String section, double score) {
        return new RuleVectorStore.Match(UUID.randomUUID(), document, version, section + " text.", "Rules reference",
                com.boardgamemaster.rules.domain.RuleSourceType.RULEBOOK, "1", "en", "Original fixture", section, null, 0, 0, 10, score);
    }

    @Test void emptyAnswerSearchRetriesOnceInRulebookWordingWithTheSameThreshold() throws Exception {
        // Dixit, live: "How do I win?" ranked "End of the game" first at 0.38, below the 0.5 threshold.
        var rewriteClient = mock(QueryRewriteClient.class);
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5),
                new RuleDuplicateSuppressor(), (query, candidates) -> candidates,
                new ContextualQueryRewriter(rewriteClient, new com.fasterxml.jackson.databind.ObjectMapper(), new QueryRewriteProperties(true)));
        var casual = new Embedding(List.of(1f, 0f, 0f));
        var expanded = new Embedding(List.of(0f, 1f, 0f));
        String cue = "How does a player win, and when does the game end?";
        when(client.profile()).thenReturn(profile);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(client.embed("How do I win?")).thenReturn(casual);
        when(client.embed(cue)).thenReturn(expanded);
        when(store.search(eq(version), eq(profile), eq(casual), anyInt(), any())).thenReturn(List.of(scored("End of the game", .38)));
        var endOfGame = scored("End of the game", .62);
        when(store.search(eq(version), eq(profile), eq(expanded), anyInt(), any())).thenReturn(List.of(endOfGame, scored("Components", .41)));
        when(rewriteClient.rewrite(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.input());
            assertThat(data.path("question").asText()).isEqualTo("How do I win?");
            assertThat(data.path("game").asText()).isEqualTo("Dixit");
            assertThat(request.instructions()).contains("Never answer the question");
            return java.util.Optional.of(cue);
        });

        var result = service.searchQuestion(version, "How do I win?", new RuleRetrievalService.Expansion("Dixit", "2021"));
        assertThat(result.matches()).as("the retry keeps the 0.5 threshold").containsExactly(endOfGame);
        assertThat(result.resolvedQuestion()).as("a retry cue is never shown to the answer step").isNull();
        verify(rewriteClient, times(1)).rewrite(any());
    }

    @Test void retryIsSkippedWhenTheFirstSearchMatchesOrForPlainSearch() {
        var rewriteClient = mock(QueryRewriteClient.class);
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5),
                new RuleDuplicateSuppressor(), (query, candidates) -> candidates,
                new ContextualQueryRewriter(rewriteClient, new com.fasterxml.jackson.databind.ObjectMapper(), new QueryRewriteProperties(true)));
        var vector = new Embedding(List.of(1f, 0f, 0f));
        when(client.profile()).thenReturn(profile);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(client.embed(anyString())).thenReturn(vector);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of(scored("End of the game", .7)));
        assertThat(service.searchQuestion(version, "How does the game end?", new RuleRetrievalService.Expansion("Dixit", null)).matches()).hasSize(1);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of(scored("Setup", .2)));
        assertThat(service.search(version, "How do I win?", null).matches()).isEmpty();
        verifyNoInteractions(rewriteClient);
    }

    @Test void failedOrDeclinedRetryKeepsTheEmptyResult() {
        var rewriteClient = mock(QueryRewriteClient.class);
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5),
                new RuleDuplicateSuppressor(), (query, candidates) -> candidates,
                new ContextualQueryRewriter(rewriteClient, new com.fasterxml.jackson.databind.ObjectMapper(), new QueryRewriteProperties(true)));
        var vector = new Embedding(List.of(1f, 0f, 0f));
        when(client.profile()).thenReturn(profile);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(client.embed(anyString())).thenReturn(vector);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of(scored("Setup", .3)));
        when(rewriteClient.rewrite(any())).thenReturn(java.util.Optional.empty())
                .thenThrow(new LlmException(LlmException.Code.LLM_TIMEOUT, "safe error"));
        var expansion = new RuleRetrievalService.Expansion(null, null);
        assertThat(service.searchQuestion(version, "What's the weather?", expansion).matches()).isEmpty();
        assertThat(service.searchQuestion(version, "How do I win?", expansion).matches()).isEmpty();
        verify(client, times(2)).embed(anyString());
        verify(store, times(2)).search(eq(version), eq(profile), eq(vector), anyInt(), any());
    }

    @Test void disabledRewritingDisablesTheRetry() {
        var rewriteClient = mock(QueryRewriteClient.class);
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5),
                new RuleDuplicateSuppressor(), (query, candidates) -> candidates,
                new ContextualQueryRewriter(rewriteClient, new com.fasterxml.jackson.databind.ObjectMapper(), new QueryRewriteProperties(false)));
        var vector = new Embedding(List.of(1f, 0f, 0f));
        when(client.profile()).thenReturn(profile);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(client.embed(anyString())).thenReturn(vector);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of(scored("Setup", .3)));
        assertThat(service.searchQuestion(version, "How do I win?", new RuleRetrievalService.Expansion("Dixit", null)).matches()).isEmpty();
        verifyNoInteractions(rewriteClient);
    }
    private final RuleDocumentService documents = mock(RuleDocumentService.class);
    private final RuleVectorStore store = mock(RuleVectorStore.class);
    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final EmbeddingProperties config = new EmbeddingProperties("disabled", "test", "", 3, Duration.ofSeconds(1), 100, 2);
    private final EmbeddingGateway gateway = new EmbeddingGateway(client, config);
    private final EmbeddingProfile profile = new EmbeddingProfile("fixture", "test", 3);
    private final UUID version = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();

    @Test void emptyIndexDoesNotCallProvider() {
        when(client.profile()).thenReturn(profile);
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5));
        assertThat(service.search(version, "query", null).matches()).isEmpty();
        verify(client, never()).embed(anyString());
    }

    @Test void validatesQueryAndResultLimitBeforeProviderCalls() {
        var service = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5));
        for (String query : new String[]{" ", "x".repeat(101)}) {
            assertThatThrownBy(() -> service.search(version, query, null)).isInstanceOf(RetrievalException.class);
        }
        assertThatThrownBy(() -> service.search(version, "query", 51)).isInstanceOf(RetrievalException.class);
        verifyNoInteractions(client, store);
        assertThatThrownBy(() -> new RetrievalProperties(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void checksSynchronousIndexLimitBeforeProviderCalls() {
        when(client.profile()).thenReturn(profile);
        when(store.counts(version, document, profile)).thenReturn(new RuleVectorStore.Counts(3, 0));
        var service = new RuleEmbeddingService(documents, store, gateway, config);
        assertThatThrownBy(() -> service.index(version, document)).isInstanceOfSatisfying(RetrievalException.class,
                e -> assertThat(e.code()).isEqualTo(RetrievalException.Code.INDEX_LIMIT_EXCEEDED));
        verify(client, never()).embed(anyString());
    }

    @Test void validatesAllChunksBeforeGeneratingAnyEmbeddings() {
        when(client.profile()).thenReturn(profile);
        when(store.counts(version, document, profile)).thenReturn(new RuleVectorStore.Counts(2, 0));
        when(store.pending(version, document, profile)).thenReturn(List.of(
                new RuleVectorStore.ChunkText(UUID.randomUUID(), "Short text"),
                new RuleVectorStore.ChunkText(UUID.randomUUID(), "x".repeat(101))));
        assertThatThrownBy(() -> new RuleEmbeddingService(documents, store, gateway, config).index(version, document)).isInstanceOf(RetrievalException.class);
        verify(client, never()).embed(anyString());
        verify(store, never()).store(any(), any(), any(), anyList());
    }

    @Test void badProviderVectorCannotReachStorage() {
        when(client.profile()).thenReturn(profile);
        when(store.counts(version, document, profile)).thenReturn(new RuleVectorStore.Counts(1, 0));
        when(store.pending(version, document, profile)).thenReturn(List.of(new RuleVectorStore.ChunkText(UUID.randomUUID(), "text")));
        when(client.embed("text")).thenReturn(new Embedding(List.of(1f, 0f)));
        assertThatThrownBy(() -> new RuleEmbeddingService(documents, store, gateway, config).index(version, document)).isInstanceOf(EmbeddingException.class);
        verify(store, never()).store(any(), any(), any(), anyList());
    }
}
