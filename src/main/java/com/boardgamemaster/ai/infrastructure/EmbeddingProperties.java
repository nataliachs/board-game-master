package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.EmbeddingProfile;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.embedding")
public record EmbeddingProperties(String provider, String model, String apiKey, Integer dimensions,
        Duration timeout, int maxInputCharacters, int maxDocumentChunks) {
    public EmbeddingProperties {
        provider = provider == null || provider.isBlank() ? "disabled" : provider.strip();
        model = model == null || model.isBlank() ? "text-embedding-3-small" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        dimensions = dimensions == null ? 1536 : dimensions;
        new EmbeddingProfile(provider, model, dimensions);
        if ((!provider.equals("disabled") && !provider.equals("openai")) || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0
                || maxInputCharacters < 1 || maxInputCharacters > 16000
                || maxDocumentChunks < 1 || maxDocumentChunks > 10000) {
            throw new IllegalArgumentException("Invalid embedding configuration");
        }
        if (provider.equals("openai") && (apiKey.isBlank() || apiKey.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("EMBEDDING_API_KEY is required when OpenAI embeddings are enabled");
        }
    }

    @Override public String toString() { return "EmbeddingProperties[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]"; }
}
