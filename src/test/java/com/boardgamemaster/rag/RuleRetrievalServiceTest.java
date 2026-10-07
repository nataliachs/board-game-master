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
