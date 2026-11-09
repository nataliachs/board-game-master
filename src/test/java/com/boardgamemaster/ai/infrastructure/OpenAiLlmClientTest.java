package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class OpenAiLlmClientTest {
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
            "gpt-4.1-mini,true", "gpt-4.1-mini,false", "gpt-5.6-luna,true", "gpt-5.6-luna,false"})
    void configuredOpenAiWiresReviewAndRewriteServicesWithoutCallingProvider(String model, boolean enabled) {
        context().withUserConfiguration(
                com.boardgamemaster.rag.application.EvidenceReviewService.class,
                com.boardgamemaster.rag.application.ContextualQueryRewriter.class)
                .withPropertyValues("boardgame.llm.provider=openai", "boardgame.llm.api-key=fixture-key",
                        "boardgame.llm.model=" + model, "boardgame.evidence-review.enabled=" + enabled,
                        "boardgame.query-rewrite.enabled=" + enabled)
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    var review = app.getBean(com.boardgamemaster.rag.application.EvidenceReviewService.class);
                    assertThat(review.enabled()).isEqualTo(enabled);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(review, "client"))
                            .isSameAs(app.getBean(com.boardgamemaster.ai.EvidenceReviewClient.class));
                    var rewrite = app.getBean(com.boardgamemaster.rag.application.ContextualQueryRewriter.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(rewrite, "client"))
                            .isSameAs(app.getBean(com.boardgamemaster.ai.QueryRewriteClient.class));
                    assertThat(app).hasSingleBean(LlmClient.class);
                });
    }
    @ParameterizedTest @ValueSource(strings = {"gpt-4.1-mini", "gpt-5.6-luna"})
    void reviewSchemasReuseCompatibleParameters(String model) throws Exception {
        var client = client(new LlmProperties("openai", model, "fixture-key", Duration.ofSeconds(3), 800, "none"));
        response = envelope(mapper.writeValueAsString(Map.of("chunkIds", List.of(chunkId))));
        assertThat(client.selectEvidence(input)).containsExactly(chunkId);
        var sent = mapper.readTree(request);
        assertThat(sent.path("text").path("format").path("name").asText()).isEqualTo("relevant_rule_evidence");
        assertThat(sent.path("store").asBoolean()).isFalse();
        assertThat(sent.has("temperature")).isEqualTo(model.equals("gpt-4.1-mini"));
        assertThat(sent.has("reasoning")).isEqualTo(model.equals("gpt-5.6-luna"));
        response = envelope("{\"supported\":true}");
        assertThat(client.supportsAnswer(input)).isTrue();
        assertThat(mapper.readTree(request).path("text").path("format").path("name").asText()).isEqualTo("cited_answer_support");
    }
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"chunkIds\":null}", "{\"chunkIds\":[1]}",
            "{\"chunkIds\":[\"invalid\"]}", "{\"chunkIds\":[],\"extra\":true}"})
    void malformedRelevanceContractRejected(String body) throws Exception {
        response = envelope(body);
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).selectEvidence(input)).isInstanceOf(LlmException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"supported\":\"true\"}", "{\"supported\":true,\"extra\":1}"})
    void malformedSupportContractRejected(String body) throws Exception {
        response = envelope(body);
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).supportsAnswer(input)).isInstanceOf(LlmException.class);
    }
    @Test void reviewRefusalDeclinesBothContracts() {
        response = """
                {"status":"completed","output":[{"type":"message","role":"assistant","status":"completed",
                "content":[{"type":"refusal","refusal":"Cannot verify."}]}]}
                """;
        assertThat(client(Duration.ofSeconds(3)).selectEvidence(input)).isEmpty();
        assertThat(client(Duration.ofSeconds(3)).supportsAnswer(input)).isFalse();
    }
    @Test void duplicateAndOversizedSelectionsAreRejected() throws Exception {
        response = envelope(mapper.writeValueAsString(Map.of("chunkIds", List.of(chunkId, chunkId))));
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).selectEvidence(input)).isInstanceOf(LlmException.class);
        response = envelope(mapper.writeValueAsString(Map.of("chunkIds", java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> UUID.randomUUID()).toList())));
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).selectEvidence(input)).isInstanceOf(LlmException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"gpt-4.1-mini", "gpt-5.6-luna"})
    void rewriteUsesSeparateStrictContractAndModelCompatibleParameters(String model) throws Exception {
        response = envelope("{\"query\":\"What are the consequences of keeping all four starting mission cards?\",\"resolved\":true}");
        var properties = new LlmProperties("openai", model, "fixture-key", Duration.ofSeconds(3), 800, "none");
        assertThat(client(properties).rewrite(input)).contains("What are the consequences of keeping all four starting mission cards?");
        var data = mapper.readTree(request);
        assertThat(data.path("store").asBoolean()).isFalse();
        assertThat(data.has("tools")).isFalse();
        assertThat(data.path("text").path("format").path("name").asText()).isEqualTo("standalone_rule_query");
        assertThat(data.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(data.has("temperature")).isEqualTo(model.equals("gpt-4.1-mini"));
        assertThat(data.has("reasoning")).isEqualTo(model.equals("gpt-5.6-luna"));
    }

    @Test void rewriteUnresolvedAndRefusalDoNotBecomeQueries() throws Exception {
        response = envelope("{\"query\":\"\",\"resolved\":false}");
        assertThat(client(Duration.ofSeconds(3)).rewrite(input)).isEmpty();
        response = """
                {"status":"completed","output":[{"type":"message","role":"assistant","status":"completed",
                "content":[{"type":"refusal","refusal":"Cannot resolve."}]}]}
                """;
        assertThat(client(Duration.ofSeconds(3)).rewrite(input)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{}", "[]", "{\"query\":\"x\",\"resolved\":\"true\"}",
            "{\"query\":\" \n\",\"resolved\":true}", "{\"query\":\"x\",\"resolved\":false}",
            "{\"query\":\"x\",\"resolved\":true,\"extra\":1}"})
    void malformedRewriteContractIsRejected(String body) throws Exception {
        response = envelope(body);
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).rewrite(input)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.LLM_INVALID_RESPONSE));
    }
    private HttpServer server;
    private String response;
    private int status = 200;
    private long delay;
    private String request;
    private String authorization;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID chunkId = UUID.randomUUID();
    private final LlmRequest input = new LlmRequest("Trusted instructions", "Untrusted evidence and question");

    @BeforeEach void start() throws Exception {
        response = envelope(mapper.writeValueAsString(Map.of("answer", "Move one space.", "sufficientEvidence", true,
                "citations", List.of(Map.of("chunkId", chunkId, "quote", "Move one space.")))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            try {
                if (delay > 0) { Thread.sleep(delay); }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* Timeout test intentionally closes the connection. */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    private String envelope(String text) throws Exception {
        return mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "role", "assistant",
                "status", "completed", "content", List.of(Map.of("type", "output_text", "text", text))))));
    }
    private LlmProperties config(Duration timeout) { return new LlmProperties("openai", "gpt-4.1-mini", "fixture-key", timeout, 800); }
    private OpenAiLlmClient client(Duration timeout) {
        return client(config(timeout));
    }
    private OpenAiLlmClient client(LlmProperties properties) {
        return new OpenAiLlmClient(properties, mapper, HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"));
    }

    @Test void sendsStructuredNonstoredLowRandomnessRequestAndParsesOutputArray() throws Exception {
        var answer = client(Duration.ofSeconds(3)).generate(input);
        assertThat(answer.answer()).isEqualTo("Move one space.");
        assertThat(answer.sufficientEvidence()).isTrue();
        assertThat(answer.citations()).containsExactly(new LlmResponse.Citation(chunkId, "Move one space."));
        var json = mapper.readTree(request);
        assertThat(json.path("model").asText()).isEqualTo("gpt-4.1-mini");
        assertThat(json.path("instructions").asText()).isEqualTo(input.instructions());
        assertThat(json.path("input").asText()).isEqualTo(input.input());
        assertThat(json.path("store").booleanValue()).isFalse();
        assertThat(json.path("temperature").asDouble()).isZero();
        assertThat(json.path("max_output_tokens").asInt()).isEqualTo(800);
        assertThat(json.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(json.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(json.path("text").path("format").path("schema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(authorization).isEqualTo("Bearer fixture-key");
        assertThat(json.has("tools")).isFalse();
        assertThat(json.has("reasoning")).isFalse();
    }

    private LlmProperties luna(String effort) {
        return new LlmProperties("openai", "gpt-5.6-luna", "fixture-key", Duration.ofSeconds(3), 4096, effort);
    }

    @ParameterizedTest @ValueSource(strings = {"none", "low", "medium", "high", "xhigh", "max"})
    void lunaSendsEffortWithoutSamplingParametersAndPreservesAnswerContract(String effort) throws Exception {
        var original = mapper.readTree(response);
        response = mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(
                Map.of("type", "reasoning", "id", "rs_fixture", "summary", List.of(
                        Map.of("type", "summary_text", "text", "Private reasoning summary"))),
                original.path("output").get(0))));
        var result = client(luna(effort)).generate(input);
        assertThat(result.answer()).isEqualTo("Move one space.");
        assertThat(result.citations()).containsExactly(new LlmResponse.Citation(chunkId, "Move one space."));
        assertThat(result.toString()).doesNotContain("Private reasoning");
        var json = mapper.readTree(request);
        assertThat(json.path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(json.path("reasoning").path("effort").asText()).isEqualTo(effort);
        assertThat(json.has("temperature")).isFalse();
        assertThat(json.has("top_p")).isFalse();
        assertThat(json.has("tools")).isFalse();
        assertThat(json.path("store").asBoolean()).isFalse();
        assertThat(json.path("max_output_tokens").asInt()).isEqualTo(4096);
        assertThat(json.path("instructions").asText()).isEqualTo(input.instructions());
        assertThat(json.path("input").asText()).isEqualTo(input.input());
        assertThat(json.path("text").path("format").path("strict").asBoolean()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"type\":\"reasoning\",\"id\":\"rs_fixture\",\"summary\":[]}",
            "{\"type\":\"reasoning\",\"summary\":[]}",
            "{\"type\":\"reasoning\",\"id\":\"rs_fixture\",\"summary\":{},\"status\":\"completed\"}",
            "{\"type\":\"reasoning\",\"id\":\"rs_fixture\",\"summary\":[],\"status\":\"incomplete\"}",
            "{\"type\":\"reasoning\",\"id\":\"rs_fixture\",\"summary\":[{\"type\":\"output_text\",\"text\":\"hidden\"}]}"})
    void reasoningAloneOrMalformedReasoningCannotBecomeAnAnswer(String item) {
        response = "{\"status\":\"completed\",\"output\":[" + item + "]}";
        assertThatThrownBy(() -> client(luna("low")).generate(input)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.LLM_INVALID_RESPONSE));
    }

    @ParameterizedTest @ValueSource(strings = {"in_progress", "incomplete", "unknown"})
    void unfinishedReasoningRejectsEvenAnOtherwiseValidAnswer(String reasoningStatus) throws Exception {
        var original = mapper.readTree(response);
        response = mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(
                Map.of("type", "reasoning", "id", "rs_fixture", "summary", List.of(), "status", reasoningStatus),
                original.path("output").get(0))));
        assertThatThrownBy(() -> client(luna("low")).generate(input)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.LLM_INVALID_RESPONSE));
    }

    @Test void lunaAcceptsMessageWithoutReasoningAndCompletedReasoningWithoutSummaryText() throws Exception {
        assertThat(client(luna("none")).generate(input).answer()).isEqualTo("Move one space.");
        var original = mapper.readTree(response);
        response = mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(
                Map.of("type", "reasoning", "id", "rs_fixture", "summary", List.of(), "status", "completed",
                        "encrypted_content", "private-encrypted-fixture"), original.path("output").get(0))));
        assertThat(client(luna("low")).generate(input).answer()).isEqualTo("Move one space.");
    }

    @Test void lunaAcceptsEmptyReasoningSummaryAndRefusalButStillRejectsTools() throws Exception {
        var reasoning = Map.of("type", "reasoning", "id", "rs_fixture", "summary", List.of());
        var refusal = Map.of("type", "message", "role", "assistant", "status", "completed", "content",
                List.of(Map.of("type", "refusal", "refusal", "Cannot answer.")));
        response = mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(reasoning, refusal)));
        assertThat(client(luna("low")).generate(input).sufficientEvidence()).isFalse();
        response = mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(reasoning, refusal,
                Map.of("type", "function_call", "name", "unrequested"))));
        assertThatThrownBy(() -> client(luna("low")).generate(input)).isInstanceOf(LlmException.class);
    }

    @Test void lunaRejectsTokenBudgetTruncationEvenWithValidAnswerText() throws Exception {
        var original = mapper.readTree(response);
        response = mapper.writeValueAsString(Map.of("status", "incomplete", "incomplete_details",
                Map.of("reason", "max_output_tokens"), "output", original.path("output")));
        assertThatThrownBy(() -> client(luna("high")).generate(input)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.LLM_INVALID_RESPONSE));
    }

    @Test void lunaConfigurationBindsAndDefaultsEffortWithoutCallingProvider() {
        context().withPropertyValues("boardgame.llm.provider=openai", "boardgame.llm.api-key=fixture-key",
                "boardgame.llm.model=gpt-5.6-luna", "boardgame.llm.reasoning-effort=low").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(LlmProperties.class).reasoningEffort()).isEqualTo("low");
        });
        assertThat(luna(null).reasoningEffort()).isEqualTo("none");
        assertThat(luna(" ").reasoningEffort()).isEqualTo("none");
    }

    @ParameterizedTest @CsvSource({"400,LLM_PROVIDER_UNAVAILABLE", "401,LLM_PROVIDER_UNAVAILABLE", "429,LLM_RATE_LIMITED",
            "500,LLM_PROVIDER_UNAVAILABLE", "302,LLM_PROVIDER_UNAVAILABLE"})
    void errorsAreSafe(int code, String expected) {
        status = code; response = "fixture-key internal provider body and private rule text";
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).generate(input)).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.code().name()).isEqualTo(expected);
            assertThat(e.getMessage()).doesNotContain("fixture-key", "private rule", "internal");
            assertThat(e.getCause()).isNull();
        });
    }

    @ParameterizedTest @ValueSource(strings = {"null", "not-json", "{}", "{\"status\":\"incomplete\",\"output\":[]}",
            "{\"status\":\"failed\",\"output\":[]}", "{\"status\":\"completed\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"user\",\"status\":\"completed\",\"content\":[]}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"incomplete\",\"content\":[]}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"bad-json\"}]}]}"})
    void invalidEnvelopesFailClosed(String body) {
        response = body;
        assertInvalid();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{}", "[]",
            "{\"answer\":\"answer\",\"sufficientEvidence\":\"true\",\"citations\":[]}",
            "{\"answer\":\"answer\",\"sufficientEvidence\":true,\"citations\":[]}",
            "{\"answer\":\"answer\",\"sufficientEvidence\":true,\"citations\":[{\"chunkId\":\"bad-id\",\"quote\":\"text\"}]}",
            "{\"answer\":\"answer\",\"sufficientEvidence\":true,\"citations\":[{\"chunkId\":\"00000000-0000-0000-0000-000000000001\",\"quote\":\" \"}]}",
            "{\"answer\":\"answer\",\"sufficientEvidence\":false,\"citations\":[],\"extra\":\"field\"}"})
    void invalidAnswerContractsFailClosed(String text) throws Exception { response = envelope(text); assertInvalid(); }

    private void assertInvalid() {
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).generate(input)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.LLM_INVALID_RESPONSE));
    }

    @Test void refusalIsInsufficientEvidence() {
        response = """
                {"status":"completed","output":[{"type":"message","role":"assistant","status":"completed",
                "content":[{"type":"refusal","refusal":"Cannot answer."}]}]}
                """;
        assertThat(client(Duration.ofSeconds(3)).generate(input).sufficientEvidence()).isFalse();
    }

    @Test void timeoutIsMeaningful() {
        delay = 500;
        assertThatThrownBy(() -> client(Duration.ofMillis(100)).generate(input)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.LLM_TIMEOUT));
    }

    @Test void malformedOversizedAndMutableContractsAreRejectedOrCopied() {
        assertThatThrownBy(() -> new LlmRequest(" ", "input")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmResponse("x".repeat(8001), true, List.of())).isInstanceOf(LlmException.class);
        var citations = new ArrayList<>(List.of(new LlmResponse.Citation(chunkId, "text")));
        var answer = new LlmResponse("answer", true, citations);
        citations.clear();
        assertThat(answer.citations()).hasSize(1);
        assertThat(input.toString()).doesNotContain("Trusted", "Untrusted");
        assertThat(answer.toString()).doesNotContain("answer", "text");
        assertThat(config(Duration.ofSeconds(3)).toString()).contains("REDACTED").doesNotContain("fixture-key");
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(LlmConfiguration.class).withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("boardgame.llm.timeout=30s", "boardgame.llm.max-output-tokens=800");
    }
    @Test void blankLegacySettingsRemainDisabled() {
        context().withPropertyValues("boardgame.llm.provider=", "boardgame.llm.model=", "boardgame.llm.api-key=").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(LlmProperties.class).model()).isEqualTo("gpt-4.1-mini");
            assertThatThrownBy(() -> c.getBean(LlmClient.class).generate(input)).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.code()).isEqualTo(LlmException.Code.LLM_NOT_CONFIGURED));
        });
    }
    private Map<String, Object> draftJson(Object lessons) {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("name", "Moon Party"); draft.put("publisher", ""); draft.put("category", "Party");
        draft.put("minPlayers", 3); draft.put("maxPlayers", 8); draft.put("playTimeMinutes", 30);
        draft.put("edition", "Base game"); draft.put("language", "en"); draft.put("description", "A fixture.");
        draft.put("setupSteps", List.of(Map.of("title", "Place the board", "instruction", "Middle of the table.",
                "notes", List.of(), "warnings", List.of("Keep cards hidden."))));
        draft.put("lessons", lessons);
        return draft;
    }
    private Map<String, Object> draftLesson() {
        var lesson = new LinkedHashMap<String, Object>();
        lesson.put("title", "Your goal"); lesson.put("explanation", "Score the most points.");
        lesson.put("quick", true); lesson.put("standard", true); lesson.put("detailed", true); lesson.put("startsSetup", false);
        lesson.put("examples", List.of()); lesson.put("tips", List.of("Have fun.")); lesson.put("references", List.of("Page 1"));
        return lesson;
    }

    @Test void gameDraftUsesItsOwnBudgetAndStrictSchemaAndParsesTheDraft() throws Exception {
        var client = new OpenAiLlmClient(config(Duration.ofSeconds(3)), new GameDraftProperties(12000, Duration.ofSeconds(5)), mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"));
        response = envelope(mapper.writeValueAsString(draftJson(List.of(draftLesson()))));
        var draft = client.draftGame(input);
        assertThat(draft.name()).isEqualTo("Moon Party");
        assertThat(draft.maxPlayers()).isEqualTo(8);
        assertThat(draft.language()).isEqualTo("en");
        assertThat(draft.setupSteps()).singleElement().satisfies(s -> assertThat(s.warnings()).containsExactly("Keep cards hidden."));
        assertThat(draft.lessons()).singleElement().satisfies(l -> {
            assertThat(l.quick()).isTrue();
            assertThat(l.references()).containsExactly("Page 1");
        });
        var sent = mapper.readTree(request);
        assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(12000);
        assertThat(sent.path("store").asBoolean()).isFalse();
        var format = sent.path("text").path("format");
        assertThat(format.path("name").asText()).isEqualTo("game_teaching_draft");
        assertThat(format.path("strict").asBoolean()).isTrue();
        assertThat(format.path("schema").path("required")).hasSize(11);
        assertThat(format.path("schema").path("properties").path("lessons").path("items").path("additionalProperties").asBoolean()).isFalse();
    }

    @Test void malformedOrRefusedGameDraftsAreRejected() throws Exception {
        var client = client(Duration.ofSeconds(3));
        var extraLessonField = new LinkedHashMap<>(draftLesson()); extraLessonField.put("extra", 1);
        for (var body : List.of("{}", mapper.writeValueAsString(draftJson("not a list")),
                mapper.writeValueAsString(draftJson(List.of(extraLessonField))))) {
            response = envelope(body);
            assertThatThrownBy(() -> client.draftGame(input)).isInstanceOf(LlmException.class);
        }
        response = mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "role", "assistant",
                "status", "completed", "content", List.of(Map.of("type", "refusal", "refusal", "No."))))));
        assertThatThrownBy(() -> client.draftGame(input)).isInstanceOf(LlmException.class).hasMessageContaining("declined");
        response = mapper.writeValueAsString(Map.of("status", "incomplete", "output", List.of()));
        assertThatThrownBy(() -> client.draftGame(input)).isInstanceOf(LlmException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"boardgame.game-draft.max-output-tokens=999", "boardgame.game-draft.max-output-tokens=32001",
            "boardgame.game-draft.timeout=281s", "boardgame.game-draft.timeout=0s"})
    void invalidGameDraftConfigurationFailsStartup(String setting) { context().withPropertyValues(setting).run(c -> assertThat(c).hasFailed()); }

    @Test void disabledProviderGivesAClearGameDraftError() {
        context().run(app -> assertThatThrownBy(() -> app.getBean(GameDraftClient.class).draftGame(input))
                .isInstanceOf(LlmException.class).hasMessageContaining("import a game from a rulebook"));
    }

    @ParameterizedTest @ValueSource(strings = {"boardgame.llm.provider=unknown", "boardgame.llm.timeout=0s", "boardgame.llm.timeout=121s",
            "boardgame.llm.max-output-tokens=0", "boardgame.llm.max-output-tokens=4097", "boardgame.llm.provider=openai",
            "boardgame.llm.reasoning-effort=invalid", "boardgame.llm.reasoning-effort=low"})
    void invalidConfigurationFailsStartup(String setting) { context().withPropertyValues(setting).run(c -> assertThat(c).hasFailed()); }
}
