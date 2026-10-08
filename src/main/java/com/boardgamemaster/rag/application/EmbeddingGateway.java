package com.boardgamemaster.rag.application;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.ai.infrastructure.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingGateway {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingGateway.class);
    private final EmbeddingClient client;
    private final EmbeddingProperties config;
    public EmbeddingGateway(EmbeddingClient client, EmbeddingProperties config) { this.client = client; this.config = config; }

    public EmbeddingProfile profile() { return client.profile(); }
    public int maxInputCharacters() { return config.maxInputCharacters(); }
    public void requireConfigured() {
        if (profile().provider().equals("disabled")) {
            throw new EmbeddingException(EmbeddingException.Code.EMBEDDING_NOT_CONFIGURED,
                    "Enable and configure an embedding provider to index documents or search rules");
        }
    }

    public void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > config.maxInputCharacters()) {
            throw new RetrievalException(RetrievalException.Code.INVALID_SEARCH,
                    "Embedding input must be nonblank and within the configured character limit");
        }
    }

    public Embedding embed(String text) {
        requireConfigured();
        validateText(text);
        long started = System.nanoTime();
        try {
            var embedding = client.embed(text);
            if (embedding == null) { throw new EmbeddingException(EmbeddingException.Code.EMBEDDING_INVALID_RESPONSE, "Embedding provider returned no vector"); }
            embedding.requireDimensions(profile().dimensions());
            return embedding;
        } finally {
            LOG.info("Embedding request completed: provider={}, elapsedMs={}", profile().provider(), (System.nanoTime() - started) / 1_000_000);
        }
    }
}
