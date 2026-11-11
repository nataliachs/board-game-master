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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector", Double.class))
                .isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                String.class)).containsExactlyInAnyOrder("flyway_schema_history", "games", "game_versions", "rule_documents", "rule_chunks", "rule_chunk_embeddings",
                        "teaching_flows", "teaching_sections", "game_sessions", "conversation_messages", "setup_flows", "setup_steps", "app_settings");
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

    @Test
    void browserTeachingWorksWithAllAiProvidersDisabled() throws Exception {
        var home = get("/");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Ticket to Ride", "value=\"STANDARD\" checked").doesNotContain("Catan");
        String gameId = jdbc.queryForObject("SELECT id::text FROM games WHERE slug = 'ticket-to-ride'", String.class);
        try (HttpClient browser = HttpClient.newHttpClient()) {
            var created = browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ui/sessions"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("gameId=" + gameId + "&playerCount=4"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(302);
            var location = URI.create(created.headers().firstValue("Location").orElseThrow());
            assertThat(location.toString()).doesNotContain("jsessionid");
            var lesson = get(location.getPath());
            assertThat(lesson.statusCode()).isEqualTo(200);
            assertThat(lesson.body()).contains("Lesson 1 of 11", "Voice input isn't enabled here", "id=\"question\"")
                    .doesNotContain("id=\"speak\"", "id=\"upload-audio\"");
            assertThat(lesson.body()).contains("Welcome to Ticket to Ride");
        }
    }

    @Test
    void adminPagesCreateAPlayableGameWithoutAiProviders() throws Exception {
        assertThat(get("/admin").body()).contains("Your game library", "Ticket to Ride", "Add a game");
        assertThat(get("/admin/games/new").statusCode()).isEqualTo(200);
        var created = post("/admin/games", "name=Admin+Demo&minPlayers=2&maxPlayers=4&duration=20&category=Party&edition=Base+game&language=en");
        assertThat(created.statusCode()).isEqualTo(302);
        String editor = URI.create(created.headers().firstValue("Location").orElseThrow()).getPath();
        String gameId = editor.substring(editor.lastIndexOf('/') + 1);
        assertThat(jdbc.queryForObject("SELECT slug FROM games WHERE id = ?::uuid", String.class, gameId)).isEqualTo("admin-demo");

        var setup = post(editor + "/setup", "stepOrder=1&stepTitle=Lay+out+cards&stepKey=&stepInstruction=Put+the+cards+face+down.&stepNotes=&stepWarnings="
                + "&stepOrder=2&stepTitle=&stepKey=&stepInstruction=&stepNotes=&stepWarnings=");
        assertThat(setup.statusCode()).isEqualTo(302);
        String lessonRow = "&lessonKey=&lessonExamples=&lessonTips=&lessonReferences=";
        var missingStyle = post(editor + "/lessons", "lessonOrder=1&lessonTitle=Welcome&lessonExplanation=Hello." + lessonRow + "&lessonQuick=0&setupTrigger=");
        assertThat(missingStyle.statusCode()).isEqualTo(200);
        assertThat(missingStyle.body()).contains("Every teaching style needs at least one lesson", "Welcome");
        var lessons = post(editor + "/lessons", "lessonOrder=1&lessonTitle=Welcome&lessonExplanation=Hello." + lessonRow
                + "&lessonOrder=2&lessonTitle=Set+up&lessonExplanation=Continue+to+set+up." + lessonRow
                + "&lessonQuick=0&lessonStandard=0&lessonDetailed=0&lessonQuick=1&lessonStandard=1&lessonDetailed=1&setupTrigger=1");
        assertThat(lessons.statusCode()).isEqualTo(302);
        assertThat(get(editor).body()).contains("LAY_OUT_CARDS", "SET_UP", "Save lessons");
        assertThat(get("/").body()).contains("Admin Demo");

        var session = post("/ui/sessions", "gameId=" + gameId + "&playerCount=2");
        assertThat(session.statusCode()).isEqualTo(302);
        assertThat(get(URI.create(session.headers().firstValue("Location").orElseThrow()).getPath()).body()).contains("Welcome", "Lesson 1 of 2");
    }

    @Test
    void adminChangesQuestionLimitsWithoutARestartAndCanReturnToTheDefaults() throws Exception {
        String count = "SELECT count(*) FROM app_settings WHERE setting_key LIKE 'paid-requests.%'";
        assertThat(get("/admin").body()).contains("Question limits", "The defaults are in use.");
        try {
            var saved = post("/admin/limits", "perClientPerHour=5000&totalPerDay=9000");
            assertThat(saved.statusCode()).isEqualTo(302);
            assertThat(saved.headers().firstValue("Location").orElseThrow()).endsWith("/admin#limits");
            assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key = 'paid-requests.per-client-per-hour'",
                    String.class)).isEqualTo("5000");
            assertThat(get("/admin").body()).contains("value=\"5000\"", "value=\"9000\"", "These limits were set here", "Use the defaults");

            assertThat(post("/admin/limits", "perClientPerHour=-1&totalPerDay=9000").statusCode()).isEqualTo(302);
            assertThat(post("/admin/limits", "perClientPerHour=abc&totalPerDay=").statusCode()).isEqualTo(302);
            assertThat(get("/admin").body()).as("rejected values change nothing").contains("value=\"5000\"", "value=\"9000\"");
        } finally {
            assertThat(post("/admin/limits", "reset=yes").statusCode()).isEqualTo(302);
        }
        assertThat(jdbc.queryForObject(count, Integer.class)).isZero();
        assertThat(get("/admin").body()).contains("The defaults are in use.").doesNotContain("value=\"5000\"");
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
