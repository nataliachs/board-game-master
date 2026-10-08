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
        return new OpenAiLlmClient(config(timeout), mapper, HttpClient.newBuilder().connectTimeout(timeout)
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
    @ParameterizedTest @ValueSource(strings = {"boardgame.llm.provider=unknown", "boardgame.llm.timeout=0s", "boardgame.llm.timeout=121s",
            "boardgame.llm.max-output-tokens=0", "boardgame.llm.max-output-tokens=4097", "boardgame.llm.provider=openai"})
    void invalidConfigurationFailsStartup(String setting) { context().withPropertyValues(setting).run(c -> assertThat(c).hasFailed()); }
}
