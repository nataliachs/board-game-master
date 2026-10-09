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
        service.searchContextual(version, "What's the risk?", context);
        verify(rewriteClient, times(1)).rewrite(any());
        verify(client, times(1)).embed(standalone);
        verify(store, times(1)).search(eq(version), eq(profile), eq(vector), anyInt(), any());
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
        service.searchContextual(version, "What's the risk?", context);
        verify(client).embed(context.retrievalQuery("What's the risk?", 100));
        service.search(version, "standalone", null);
        verify(rewriteClient, times(1)).rewrite(any());
        verify(client).embed("standalone");
    }
    @Test void contextualFollowUpReachesEmbeddingProviderOnceWithPriorTopicWithinSmallInputLimit() {
        var context = new AnswerConversation(UUID.randomUUID(), "Original game", "Edition", "en", 3, "STANDARD", "SETUP",
                "Prepare missions", "Long lesson explanation. ".repeat(40),
                List.of(new AnswerConversation.Turn("USER", "Can I keep all four mission cards?"),
                        new AnswerConversation.Turn("ASSISTANT", "Invented answer must not enter retrieval.")),
                new AnswerConversation.SetupContext("MISSIONS", "Choose mission cards", "Long dealing procedure. ".repeat(40)));
        String question = "What's the risk?";
        var vector = new Embedding(List.of(1f, 0f, 0f));
        when(client.profile()).thenReturn(profile);
        when(client.embed(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            assertThat(query).startsWith(question).contains("Can I keep all four mission cards?")
                    .doesNotContain("Invented answer").hasSizeLessThanOrEqualTo(100);
            return vector;
        });
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(store.search(eq(version), eq(profile), eq(vector), anyInt(), any())).thenReturn(List.of());
        var result = new RuleRetrievalService(documents, store, gateway, new RetrievalProperties(5))
                .searchContextual(version, question, context);
        assertThat(result.gameVersionId()).isEqualTo(version);
        assertThat(result.matches()).isEmpty();
        verify(client, times(1)).embed(anyString());
        verify(store, times(1)).search(eq(version), eq(profile), eq(vector), anyInt(), any());
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
