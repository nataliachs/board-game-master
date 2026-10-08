package com.boardgamemaster.ai.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("boardgame.tts")
public record TtsProperties(String provider, String model, String apiKey, Duration timeout, String voice) {
    @ConstructorBinding
    public TtsProperties {
        provider = provider == null || provider.isBlank() ? "disabled" : provider.strip();
        model = model == null || model.isBlank() ? "tts-1" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        SpeechConfiguration.validate(provider, model, apiKey, timeout);
        voice = voice == null || voice.isBlank() ? "alloy" : voice.strip();
        if (!java.util.Set.of("alloy", "echo", "fable", "onyx", "nova", "shimmer").contains(voice)) { throw new IllegalArgumentException("Invalid speech voice configuration"); }
    }
    @Override public String toString() { return "TtsProperties[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]"; }
}
