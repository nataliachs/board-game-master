package com.boardgamemaster.ai.infrastructure;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("boardgame.llm")
public record LlmProperties(String provider, String model, String apiKey, Duration timeout, int maxOutputTokens,
        String reasoningEffort) {
    @ConstructorBinding
    public LlmProperties {
        provider = provider == null || provider.isBlank() ? "disabled" : provider.strip();
        model = model == null || model.isBlank() ? "gpt-4.1-mini" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? "none" : reasoningEffort.strip();
        if (!Set.of("none", "low", "medium", "high", "xhigh", "max").contains(reasoningEffort)
                || (!model.equals("gpt-5.6-luna") && !reasoningEffort.equals("none"))) {
            throw new IllegalArgumentException("LLM_REASONING_EFFORT must be none unless LLM_MODEL is gpt-5.6-luna; supported efforts: none, low, medium, high, xhigh, max");
        }
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
    public LlmProperties(String provider, String model, String apiKey, Duration timeout, int maxOutputTokens) {
        this(provider, model, apiKey, timeout, maxOutputTokens, "none");
    }
    public boolean usesLuna() { return model.equals("gpt-5.6-luna"); }
    @Override public String toString() { return "LlmProperties[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]"; }
}
