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
public class OpenAiLlmClient implements LlmClient, QueryRewriteClient, EvidenceReviewClient, GameDraftClient {
    private final LlmProperties config;
    private final GameDraftProperties draftConfig;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;

    OpenAiLlmClient(LlmProperties config, ObjectMapper mapper, HttpClient http, URI endpoint) {
        this(config, GameDraftProperties.defaults(), mapper, http, endpoint);
    }
    OpenAiLlmClient(LlmProperties config, GameDraftProperties draftConfig, ObjectMapper mapper, HttpClient http, URI endpoint) {
        this.config = config; this.draftConfig = draftConfig; this.mapper = mapper; this.http = http; this.endpoint = endpoint;
    }

    @Override public GameDraft draftGame(LlmRequest input) {
        try {
            String text = outputText(send(input, "game_teaching_draft", GameDraftSchema.schema(),
                    draftConfig.maxOutputTokens(), draftConfig.timeout()), 250000);
            if (text == null) {
                throw new LlmException(LLM_INVALID_RESPONSE, "The AI declined to draft this game from the rulebook");
            }
            return GameDraftSchema.parse(mapper.readTree(text));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid(); }
    }

    @Override public LlmResponse generate(LlmRequest input) {
        try { return parse(send(input, "grounded_rule_answer", schema())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid(); }
    }

    @Override public Optional<String> rewrite(LlmRequest input) {
        var querySchema = Map.<String, Object>of("type", "object", "additionalProperties", false,
                "properties", Map.of("query", Map.of("type", "string"), "resolved", Map.of("type", "boolean")),
                "required", List.of("query", "resolved"));
        try {
            String text = outputText(send(input, "standalone_rule_query", querySchema));
            if (text == null) { return Optional.empty(); }
            var result = mapper.readTree(text);
            if (result == null || !result.isObject() || result.size() != 2
                    || !result.path("query").isTextual() || !result.path("resolved").isBoolean()) { throw invalid(); }
            String query = result.path("query").textValue().strip();
            if (!result.path("resolved").booleanValue()) {
                if (!query.isEmpty()) { throw invalid(); }
                return Optional.empty();
            }
            if (query.isBlank() || query.length() > 1000 || query.codePoints().anyMatch(Character::isISOControl)) { throw invalid(); }
            return Optional.of(query);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid(); }
    }

    @Override public List<UUID> selectEvidence(LlmRequest input) {
        var schema = Map.<String, Object>of("type", "object", "additionalProperties", false,
                "properties", Map.of("chunkIds", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("chunkIds"));
        try {
            String text = outputText(send(input, "relevant_rule_evidence", schema));
            if (text == null) { return List.of(); }
            var result = mapper.readTree(text);
            if (result == null || !result.isObject() || result.size() != 1
                    || !result.path("chunkIds").isArray() || result.path("chunkIds").size() > 20) { throw invalid(); }
            var ids = new ArrayList<UUID>();
            for (var id : result.path("chunkIds")) {
                if (!id.isTextual()) { throw invalid(); }
                try { ids.add(UUID.fromString(id.textValue())); }
                catch (IllegalArgumentException e) { throw invalid(); }
            }
            if (new HashSet<>(ids).size() != ids.size()) { throw invalid(); }
            return List.copyOf(ids);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid(); }
    }

    @Override public boolean supportsAnswer(LlmRequest input) {
        var schema = Map.<String, Object>of("type", "object", "additionalProperties", false,
                "properties", Map.of("supported", Map.of("type", "boolean")), "required", List.of("supported"));
        try {
            String text = outputText(send(input, "cited_answer_support", schema));
            if (text == null) { return false; }
            var result = mapper.readTree(text);
            if (result == null || !result.isObject() || result.size() != 1 || !result.path("supported").isBoolean()) { throw invalid(); }
            return result.path("supported").booleanValue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid(); }
    }

    private JsonNode send(LlmRequest input, String schemaName, Map<String, Object> outputSchema) {
        return send(input, schemaName, outputSchema, config.maxOutputTokens(), config.timeout());
    }

    private JsonNode send(LlmRequest input, String schemaName, Map<String, Object> outputSchema, int maxOutputTokens,
            java.time.Duration timeout) {
        try {
            var parameters = new LinkedHashMap<String, Object>(Map.of("model", config.model(), "instructions", input.instructions(),
                    "input", input.input(), "store", false, "max_output_tokens", maxOutputTokens,
                    "text", Map.of("format", Map.of("type", "json_schema", "name", schemaName,
                            "strict", true, "schema", outputSchema))));
            if (config.usesLuna()) {
                parameters.put("reasoning", Map.of("effort", config.reasoningEffort()));
            } else {
                parameters.put("temperature", 0);
            }
            String body = mapper.writeValueAsString(parameters);
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + config.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) { throw new LlmException(LLM_RATE_LIMITED, "LLM provider rate limit reached; retry later"); }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmException(LLM_PROVIDER_UNAVAILABLE, "LLM provider rejected or could not complete the request");
            }
            return mapper.readTree(response.body());
        } catch (HttpTimeoutException e) { throw new LlmException(LLM_TIMEOUT, "LLM provider request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LLM_PROVIDER_UNAVAILABLE, "LLM request was interrupted");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid();
        } catch (IOException e) { throw new LlmException(LLM_PROVIDER_UNAVAILABLE, "LLM provider could not be reached"); }
    }

    private String outputText(JsonNode response) {
        return outputText(response, 100000);
    }

    private String outputText(JsonNode response, int maxLength) {
        if (response == null || !response.path("status").asText().equals("completed")
                || !response.path("output").isArray()) { throw invalid(); }
        String text = null;
        boolean refused = false;
        for (var item : response.path("output")) {
            if (config.usesLuna() && item.path("type").asText().equals("reasoning")) {
                validateReasoning(item);
                continue;
            }
            if (!item.path("type").asText().equals("message") || !item.path("role").asText().equals("assistant")
                    || !item.path("status").asText().equals("completed") || !item.path("content").isArray()) { throw invalid(); }
            for (var content : item.path("content")) {
                if (content.path("type").asText().equals("refusal")) {
                    if (text != null || refused || !content.path("refusal").isTextual()) { throw invalid(); }
                    refused = true;
                    continue;
                }
                if (refused || text != null || !content.path("type").asText().equals("output_text") || !content.path("text").isTextual()) { throw invalid(); }
                text = content.path("text").textValue();
            }
        }
        if (refused) { return null; }
        if (text == null || text.length() > maxLength) { throw invalid(); }
        return text;
    }

    private LlmResponse parse(JsonNode response) throws com.fasterxml.jackson.core.JsonProcessingException {
        String text = outputText(response);
        if (text == null) { return new LlmResponse("", false, List.of()); }
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

    /** Reasoning is never answer evidence, returned to callers, or stored in session history. */
    private void validateReasoning(JsonNode item) {
        if (!item.path("id").isTextual() || item.path("id").asText().isBlank()
                || !item.path("summary").isArray()
                || (item.hasNonNull("status") && !item.path("status").asText().equals("completed"))) { throw invalid(); }
        for (var summary : item.path("summary")) {
            if (!summary.path("type").asText().equals("summary_text") || !summary.path("text").isTextual()) { throw invalid(); }
        }
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
