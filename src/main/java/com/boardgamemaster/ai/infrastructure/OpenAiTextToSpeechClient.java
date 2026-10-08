package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.Map;

public class OpenAiTextToSpeechClient implements TextToSpeechClient {
    private final TtsProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;
    OpenAiTextToSpeechClient(TtsProperties config, ObjectMapper mapper, HttpClient http, URI endpoint) {
        this.config = config; this.mapper = mapper; this.http = http; this.endpoint = endpoint;
    }
    @Override public SpeechAudio synthesize(String text) {
        if (text == null || text.isBlank() || text.length() > 4096) { throw new SpeechException(SpeechException.Code.TTS_TEXT_TOO_LONG, "The answer cannot be spoken within the audio limit; read the complete text instead"); }
        try {
            String body = mapper.writeValueAsString(Map.of("model", config.model(), "voice", config.voice(), "input", text, "response_format", "mp3"));
            var request = HttpRequest.newBuilder(endpoint).timeout(config.timeout()).header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = SpeechHttp.send(http, request, 4194304, "TTS");
            if (!response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].equalsIgnoreCase("audio/mpeg")) { throw new SpeechException(SpeechException.Code.TTS_INVALID_RESPONSE, "Speech provider returned invalid audio"); }
            return new SpeechAudio(response.body(), "audio/mpeg");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Could not encode speech request"); }
    }
}
