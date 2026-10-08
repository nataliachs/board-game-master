package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed production endpoint; constructor endpoint injection is for local contract tests. */
public class OpenAiSpeechToTextClient implements SpeechToTextClient {
    private final SttProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;
    OpenAiSpeechToTextClient(SttProperties config, ObjectMapper mapper, HttpClient http, URI endpoint) {
        this.config = config; this.mapper = mapper; this.http = http; this.endpoint = endpoint;
    }
    @Override public String transcribe(AudioInput audio) {
        String boundary = "bgm-" + UUID.randomUUID();
        var parts = new ArrayList<byte[]>();
        parts.add(field(boundary, "model", config.model())); parts.add(field(boundary, "response_format", "json"));
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"question." + audio.extension()
                + "\"\r\nContent-Type: " + audio.mediaType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(audio.bytes()); parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(endpoint).timeout(config.timeout()).header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArrays(parts)).build();
        var response = SpeechHttp.send(http, request, 65536, "STT");
        try {
            var json = mapper.readTree(response.body());
            if (json == null || !json.path("text").isTextual() || json.path("text").textValue().length() > 4000) { throw invalid(); }
            String text = json.path("text").textValue().strip();
            if (text.isEmpty()) { throw new SpeechException(SpeechException.Code.EMPTY_TRANSCRIPTION, "I couldn't understand the audio. Please try again or type your question"); }
            return text;
        } catch (java.io.IOException e) { throw invalid(); }
    }
    private byte[] field(String boundary, String name, String value) {
        return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
    private SpeechException invalid() { return SpeechHttp.error("STT", "INVALID_RESPONSE", "Speech provider returned an invalid transcription"); }
}
