package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.ArrayList;
import java.util.Map;
import static com.boardgamemaster.ai.EmbeddingException.Code.*;

/** Fixed HTTPS production endpoint; endpoint injection is only for local contract tests. */
public class OpenAiEmbeddingClient implements EmbeddingClient {
    private final EmbeddingProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;

    OpenAiEmbeddingClient(EmbeddingProperties config, ObjectMapper mapper, HttpClient http, URI endpoint) {
        this.config = config;
        this.mapper = mapper;
        this.http = http;
        this.endpoint = endpoint;
    }

    @Override public EmbeddingProfile profile() { return new EmbeddingProfile("openai", config.model(), config.dimensions()); }

    @Override public Embedding embed(String text) {
        if (text == null || text.isBlank() || text.length() > config.maxInputCharacters()) {
            throw new IllegalArgumentException("Embedding input must be nonblank and within the configured character limit");
        }
        try {
            String body = mapper.writeValueAsString(Map.of("model", config.model(), "input", text,
                    "dimensions", config.dimensions(), "encoding_format", "float"));
            var request = HttpRequest.newBuilder(endpoint).timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) { throw new EmbeddingException(EMBEDDING_RATE_LIMITED, "Embedding provider rate limit reached; retry later"); }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbeddingException(EMBEDDING_PROVIDER_UNAVAILABLE, "Embedding provider rejected or could not complete the request");
            }
            var json = mapper.readTree(response.body());
            if (json == null || !json.path("model").asText().equals(config.model()) || !json.path("data").isArray()
                    || json.path("data").size() != 1 || !json.path("data").get(0).path("index").isIntegralNumber()
                    || !json.path("data").get(0).path("index").canConvertToInt()
                    || json.path("data").get(0).path("index").asInt() != 0) { throw invalid(); }
            var vector = json.path("data").get(0).path("embedding");
            if (!vector.isArray() || vector.size() != config.dimensions()) { throw invalid(); }
            var values = new ArrayList<Float>();
            for (var value : vector) {
                if (!value.isNumber()) { throw invalid(); }
                values.add(value.floatValue());
            }
            var embedding = new Embedding(values);
            embedding.requireDimensions(config.dimensions());
            return embedding;
        } catch (HttpTimeoutException exception) {
            throw new EmbeddingException(EMBEDDING_TIMEOUT, "Embedding provider request timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(EMBEDDING_PROVIDER_UNAVAILABLE, "Embedding request was interrupted");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw invalid();
        } catch (IOException exception) {
            throw new EmbeddingException(EMBEDDING_PROVIDER_UNAVAILABLE, "Embedding provider could not be reached");
        }
    }

    private static EmbeddingException invalid() {
        return new EmbeddingException(EMBEDDING_INVALID_RESPONSE, "Embedding provider returned an invalid response");
    }
}
