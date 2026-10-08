package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static com.boardgamemaster.ai.LlmException.Code.*;

/** Responses API adapter. Production endpoint is fixed; local injection is for contract tests. */
public class OpenAiLlmClient implements LlmClient {
    private final LlmProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;

    OpenAiLlmClient(LlmProperties config, ObjectMapper mapper, HttpClient http, URI endpoint) {
        this.config = config; this.mapper = mapper; this.http = http; this.endpoint = endpoint;
    }

    @Override public LlmResponse generate(LlmRequest input) {
        try {
            String body = mapper.writeValueAsString(Map.of("model", config.model(), "instructions", input.instructions(),
                    "input", input.input(), "store", false, "temperature", 0, "max_output_tokens", config.maxOutputTokens(),
                    "text", Map.of("format", Map.of("type", "json_schema", "name", "grounded_rule_answer",
                            "strict", true, "schema", schema()))));
            var request = HttpRequest.newBuilder(endpoint).timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) { throw new LlmException(LLM_RATE_LIMITED, "LLM provider rate limit reached; retry later"); }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmException(LLM_PROVIDER_UNAVAILABLE, "LLM provider rejected or could not complete the request");
            }
            return parse(mapper.readTree(response.body()));
        } catch (HttpTimeoutException e) { throw new LlmException(LLM_TIMEOUT, "LLM provider request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LLM_PROVIDER_UNAVAILABLE, "LLM request was interrupted");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid();
        } catch (IOException e) { throw new LlmException(LLM_PROVIDER_UNAVAILABLE, "LLM provider could not be reached"); }
    }

    private LlmResponse parse(JsonNode response) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (response == null || !response.path("status").asText().equals("completed")
                || !response.path("output").isArray()) { throw invalid(); }
        String text = null;
        for (var item : response.path("output")) {
            if (!item.path("type").asText().equals("message") || !item.path("role").asText().equals("assistant")
                    || !item.path("status").asText().equals("completed") || !item.path("content").isArray()) { throw invalid(); }
            for (var content : item.path("content")) {
                if (content.path("type").asText().equals("refusal")) { return new LlmResponse("", false, List.of()); }
                if (text != null || !content.path("type").asText().equals("output_text") || !content.path("text").isTextual()) { throw invalid(); }
                text = content.path("text").textValue();
            }
        }
        if (text == null || text.length() > 100000) { throw invalid(); }
        var answer = mapper.readTree(text);
        if (answer == null || !answer.isObject() || answer.size() != 3 || !answer.path("answer").isTextual()
                || !answer.path("sufficientEvidence").isBoolean() || !answer.path("citations").isArray()
                || answer.path("citations").size() > 50) { throw invalid(); }
        var citations = new ArrayList<LlmResponse.Citation>();
        for (var citation : answer.path("citations")) {
            if (!citation.isObject() || citation.size() != 2 || !citation.path("chunkId").isTextual()
                    || !citation.path("quote").isTextual()) { throw invalid(); }
            try { citations.add(new LlmResponse.Citation(UUID.fromString(citation.path("chunkId").textValue()), citation.path("quote").textValue())); }
            catch (IllegalArgumentException e) { throw invalid(); }
        }
        return new LlmResponse(answer.path("answer").textValue(), answer.path("sufficientEvidence").booleanValue(), citations);
    }

    private Map<String, Object> schema() {
        return Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("answer", Map.of("type", "string"), "sufficientEvidence", Map.of("type", "boolean"),
                    "citations", Map.of("type", "array", "items", Map.of("type", "object", "additionalProperties", false,
                        "properties", Map.of("chunkId", Map.of("type", "string"), "quote", Map.of("type", "string")),
                        "required", List.of("chunkId", "quote")))),
                "required", List.of("answer", "sufficientEvidence", "citations"));
    }

    private static LlmException invalid() { return new LlmException(LLM_INVALID_RESPONSE, "LLM provider returned an invalid or incomplete response"); }
}
