package com.boardgamemaster;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired ObjectMapper mapper;

    @Test
    void migrationInstallsUsableVectorsAndIsRepeatable() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector", Double.class))
                .isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                String.class)).containsExactlyInAnyOrder("flyway_schema_history", "games", "game_versions");
    }

    @Test
    void runningServerExposesHealthAndDocumentation() throws Exception {
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("UP");
        assertThat(health.headers().firstValue("X-Request-ID")).isPresent();
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(docs.body()).path("info").path("title").asText())
                .isEqualTo("Board Game Master API");
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
    }

    @Test
    void missingResourceReturnsSafeConsistentError() throws Exception {
        var response = get("/api/v1/not-implemented");
        assertThat(response.statusCode()).isEqualTo(404);
        var error = mapper.readTree(response.body());
        assertThat(error.path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(error.path("path").asText()).isEqualTo("/api/v1/not-implemented");
        assertThat(error.path("timestamp").asText()).isNotBlank();
        assertThat(error.has("trace")).isFalse();
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
