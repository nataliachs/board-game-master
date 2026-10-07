package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class OpenAiEmbeddingClientTest {
    private HttpServer server;
    private String response = """
            {"model":"text-embedding-3-small","data":[{"index":0,"embedding":[1,0,0]}]}
            """;
    private int status = 200;
    private long delay;
    private String request;
    private String authorization;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            try {
                if (delay > 0) { Thread.sleep(delay); }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* The timeout test intentionally closes the connection. */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    private EmbeddingProperties config(Duration timeout) {
        return new EmbeddingProperties("openai", "text-embedding-3-small", "test-key-not-a-secret", 3, timeout, 4000, 1000);
    }
    private OpenAiEmbeddingClient client(Duration timeout) {
        return new OpenAiEmbeddingClient(config(timeout), mapper, HttpClient.newBuilder().connectTimeout(timeout).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings"));
    }

    @Test void sendsDocumentedRequestAndReadsValidatedVector() throws Exception {
        var client = client(Duration.ofSeconds(3));
        assertThat(client.embed("Original rule text.").values()).containsExactly(1f, 0f, 0f);
        assertThat(client.profile()).isEqualTo(new EmbeddingProfile("openai", "text-embedding-3-small", 3));
        var json = mapper.readTree(request);
        assertThat(json.path("input").asText()).isEqualTo("Original rule text.");
        assertThat(json.path("dimensions").asInt()).isEqualTo(3);
        assertThat(json.path("encoding_format").asText()).isEqualTo("float");
        assertThat(authorization).isEqualTo("Bearer test-key-not-a-secret");
    }

    @ParameterizedTest @CsvSource({"400,EMBEDDING_PROVIDER_UNAVAILABLE", "401,EMBEDDING_PROVIDER_UNAVAILABLE",
        "429,EMBEDDING_RATE_LIMITED", "500,EMBEDDING_PROVIDER_UNAVAILABLE", "302,EMBEDDING_PROVIDER_UNAVAILABLE"})
    void providerFailuresAreSafeAndMeaningful(int code, String expected) {
        status = code;
        response = "Provider debug: test-key-not-a-secret; Original rule text; internal stack";
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).embed("Original rule text"))
                .isInstanceOfSatisfying(EmbeddingException.class, e -> {
                    assertThat(e.code().name()).isEqualTo(expected);
                    assertThat(e.getMessage()).doesNotContain("test-key", "Original rule", "stack");
                    assertThat(e.getCause()).isNull();
                });
    }

    @ParameterizedTest @ValueSource(strings = {"null", "not-json", "{}",
        "{\"model\":\"other\",\"data\":[{\"index\":0,\"embedding\":[1,0,0]}]}",
        "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":1,\"embedding\":[1,0,0]}]}",
        "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}",
        "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":0,\"embedding\":[0,0,0]}]}",
        "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":0,\"embedding\":[\"1\",0,0]}]}",
        "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":0,\"embedding\":[1e300,0,0]}]}"})
    void rejectsInvalidProviderResponses(String body) {
        response = body;
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).embed("text"))
                .isInstanceOfSatisfying(EmbeddingException.class, e -> assertThat(e.code()).isEqualTo(EmbeddingException.Code.EMBEDDING_INVALID_RESPONSE));
    }

    @Test void timeoutIsBoundedAndDoesNotExposeTransportErrors() {
        delay = 500;
        assertThatThrownBy(() -> client(Duration.ofMillis(100)).embed("text"))
                .isInstanceOfSatisfying(EmbeddingException.class, e -> assertThat(e.code()).isEqualTo(EmbeddingException.Code.EMBEDDING_TIMEOUT));
    }

    @Test void invalidInputMakesNoProviderRequest() {
        var client = client(Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.embed(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embed("x".repeat(4001))).isInstanceOf(IllegalArgumentException.class);
        assertThat(request).isNull();
    }

    @Test void vectorsAreImmutableAndRejectZeroNonfiniteAndWrongDimensions() {
        var mutable = new java.util.ArrayList<>(List.of(1f, 0f));
        var vector = new Embedding(mutable);
        mutable.set(0, 3f);
        assertThat(vector.values()).containsExactly(1f, 0f);
        assertThatThrownBy(() -> vector.values().set(0, 2f)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> vector.requireDimensions(3)).isInstanceOf(EmbeddingException.class);
        assertThatThrownBy(() -> new Embedding(List.of(Float.NaN))).isInstanceOf(EmbeddingException.class);
        assertThatThrownBy(() -> new Embedding(List.of(Float.POSITIVE_INFINITY))).isInstanceOf(EmbeddingException.class);
        assertThatThrownBy(() -> new Embedding(List.of(0f))).isInstanceOf(EmbeddingException.class);
    }

    @Test void normalizesExtremeFiniteMagnitudesWithoutOverflowOrUnderflow() {
        assertThat(new Embedding(List.of(Float.MAX_VALUE, 0f)).values()).containsExactly(1f, 0f);
        assertThat(new Embedding(List.of(Float.MIN_VALUE, 0f)).values()).containsExactly(1f, 0f);
        var diagonal = new Embedding(List.of(Float.MAX_VALUE, Float.MAX_VALUE));
        assertThat(diagonal.values().getFirst()).isCloseTo((float) (1 / Math.sqrt(2)), within(.000001f));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(EmbeddingConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("boardgame.embedding.timeout=30s", "boardgame.embedding.max-input-characters=4000",
                        "boardgame.embedding.max-document-chunks=1000");
    }

    @Test void blankLegacyEnvironmentSettingsRemainDisabledWithDefaults() {
        context().withPropertyValues("boardgame.embedding.provider=", "boardgame.embedding.model=",
                "boardgame.embedding.api-key=", "boardgame.embedding.dimensions=").run(c -> {
            assertThat(c).hasNotFailed();
            var client = c.getBean(EmbeddingClient.class);
            assertThat(client.profile()).isEqualTo(new EmbeddingProfile("disabled", "text-embedding-3-small", 1536));
            assertThatThrownBy(() -> client.embed("text")).isInstanceOfSatisfying(EmbeddingException.class,
                    e -> assertThat(e.code()).isEqualTo(EmbeddingException.Code.EMBEDDING_NOT_CONFIGURED));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"boardgame.embedding.provider=unknown", "boardgame.embedding.dimensions=0",
            "boardgame.embedding.dimensions=2001", "boardgame.embedding.timeout=0s", "boardgame.embedding.max-document-chunks=0",
            "boardgame.embedding.provider=openai"})
    void invalidConfigurationFailsStartup(String setting) {
        context().withPropertyValues(setting).run(c -> assertThat(c).hasFailed());
    }

    @Test void configurationDiagnosticsRedactKey() {
        assertThat(config(Duration.ofSeconds(3)).toString()).contains("REDACTED").doesNotContain("test-key-not-a-secret");
    }
}
