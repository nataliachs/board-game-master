package com.boardgamemaster.ai.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.llm")
public record LlmProperties(String provider, String model, String apiKey, Duration timeout, int maxOutputTokens) {
    public LlmProperties {
        provider = provider == null || provider.isBlank() ? "disabled" : provider.strip();
        model = model == null || model.isBlank() ? "gpt-4.1-mini" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        if ((!provider.equals("disabled") && !provider.equals("openai")) || model.length() > 200
                || model.codePoints().anyMatch(Character::isISOControl) || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0
                || maxOutputTokens < 128 || maxOutputTokens > 4096) {
            throw new IllegalArgumentException("Invalid LLM configuration");
        }
        if (provider.equals("openai") && (apiKey.isBlank() || apiKey.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("LLM_API_KEY is required when OpenAI generation is enabled");
        }
    }
    @Override public String toString() { return "LlmProperties[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]"; }
}
