package com.boardgamemaster.ai.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("boardgame.stt")
public record SttProperties(String provider, String model, String apiKey, Duration timeout) {
    @ConstructorBinding
    public SttProperties {
        provider = provider == null || provider.isBlank() ? "disabled" : provider.strip();
        model = model == null || model.isBlank() ? "whisper-1" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        SpeechConfiguration.validate(provider, model, apiKey, timeout);
    }
    @Override public String toString() { return "SttProperties[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]"; }
}
