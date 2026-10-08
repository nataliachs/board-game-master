package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.voice.application.VoiceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SttProperties.class, TtsProperties.class, VoiceProperties.class})
public class SpeechConfiguration {
    static void validate(String provider, String model, String key, Duration timeout) {
        if (!java.util.Set.of("disabled", "openai").contains(provider) || model.isBlank() || model.length() > 200
                || model.codePoints().anyMatch(Character::isISOControl) || key.codePoints().anyMatch(Character::isISOControl)
                || (provider.equals("openai") && key.isBlank()) || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("Invalid speech configuration; enabled providers require a private API key");
        }
    }
    @Bean @ConditionalOnMissingBean(SpeechToTextClient.class)
    SpeechToTextClient speechToTextClient(SttProperties config, ObjectMapper mapper) {
        if (config.provider().equals("disabled")) { return audio -> { throw new SpeechException(SpeechException.Code.STT_NOT_CONFIGURED, "Voice input is not enabled; type your question instead"); }; }
        return new OpenAiSpeechToTextClient(config, mapper, http(config.timeout()), URI.create("https://api.openai.com/v1/audio/transcriptions"));
    }
    @Bean @ConditionalOnMissingBean(TextToSpeechClient.class)
    TextToSpeechClient textToSpeechClient(TtsProperties config, ObjectMapper mapper) {
        if (config.provider().equals("disabled")) { return text -> { throw new SpeechException(SpeechException.Code.TTS_NOT_CONFIGURED, "Spoken answers are not enabled; your text answer is available"); }; }
        return new OpenAiTextToSpeechClient(config, mapper, http(config.timeout()), URI.create("https://api.openai.com/v1/audio/speech"));
    }
    private HttpClient http(Duration timeout) { return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(); }
}
