package com.boardgamemaster.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.boardgamemaster.ai.GameDraft;
import com.boardgamemaster.ai.GameDraftClient;
import com.boardgamemaster.ai.LlmRequest;
import com.boardgamemaster.rules.application.TestPdfs;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Imports a rulebook PDF through the admin page, with a fixed draft instead of a paid AI call. */
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameImportIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final AtomicReference<String> RULEBOOK_SENT = new AtomicReference<>();

    @MockitoBean GameDraftClient drafts;

    private static GameDraft draft(LlmRequest request) {
        RULEBOOK_SENT.set(request.input());
        return new GameDraft("Moon Party", "Fixture Games", "Party", 3, 8, 30, "Base game", "en", "A fixture party game.",
                List.of(new GameDraft.Step("Place the board", "Put the board in the middle.", List.of(), List.of("Keep cards hidden.")),
                        new GameDraft.Step("Deal cards", "Deal six cards to each player.", List.of(), List.of())),
                List.of(lesson("Welcome", true, true, true, false), lesson("Your goal", true, true, true, false),
                        lesson("Prepare the table", true, true, true, true), lesson("Giving clues", false, true, true, false),
                        lesson("Worked example", false, false, true, false)));
    }
    private static GameDraft.Lesson lesson(String title, boolean q, boolean s, boolean d, boolean setup) {
        return new GameDraft.Lesson(title, title + " in plain words.", q, s, d, setup, List.of(), List.of(), List.of("Page 1"));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    static final String FILLER = "Each round one player is the storyteller and gives a clue about a secret card.";

    @Test
    void importCreatesAHiddenGameWithSetupLessonsAndPagedRulebook() throws Exception {
        org.mockito.Mockito.when(drafts.draftGame(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> draft(call.getArgument(0)));
        try (var browser = HttpClient.newHttpClient()) {
            assertThat(get(browser, "/admin/games/new").body()).contains("Import from a rulebook PDF", "name=\"ownCopy\"");

            byte[] pdf = TestPdfs.pdf(List.of(List.of("Moon Party rules", FILLER, FILLER, FILLER),
                    List.of("Scoring: the storyteller scores three points.", FILLER, FILLER)));
            var response = postMultipart(browser, "/admin/games/import", Map.of("ownCopy", "yes"), "moon-party.pdf", pdf);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
            String editor = response.headers().firstValue("Location").orElseThrow();
            UUID gameId = UUID.fromString(editor.substring(editor.lastIndexOf('/') + 1));

            assertThat(RULEBOOK_SENT.get()).contains("<!-- page: 2 -->", "storyteller scores three points");
            assertThat(jdbc.queryForObject("SELECT active FROM games WHERE id = ?", Boolean.class, gameId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT slug FROM games WHERE id = ?", String.class, gameId)).isEqualTo("moon-party");
            UUID version = jdbc.queryForObject("SELECT id FROM game_versions WHERE game_id = ? AND default_version", UUID.class, gameId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM setup_steps s JOIN setup_flows f ON f.id = s.setup_flow_id WHERE f.game_version_id = ?",
                    Integer.class, version)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT t.title FROM teaching_sections t JOIN teaching_flows f ON f.id = t.teaching_flow_id "
                    + "WHERE f.game_version_id = ? AND t.depths::text LIKE '%QUICK%' ORDER BY t.section_order", String.class, version))
                    .containsExactly("Welcome", "Your goal", "Prepare the table");
            assertThat(jdbc.queryForObject("SELECT title FROM teaching_sections t JOIN teaching_flows f ON f.id = t.teaching_flow_id "
                    + "WHERE f.game_version_id = ? AND t.setup_trigger", String.class, version)).isEqualTo("Prepare the table");
            assertThat(jdbc.queryForList("SELECT DISTINCT page_number FROM rule_chunks WHERE game_version_id = ? ORDER BY page_number",
                    Integer.class, version)).containsExactly(1, 2);

            // The editor opens with the import notice; embeddings are off in tests, so indexing is left for later.
            var page = get(browser, editor, response.headers().allValues("Set-Cookie"));
            assertThat(page.body()).contains("Moon Party", "Prepare the table", "moon-party.md");
            assertThat(get(browser, "/").body()).doesNotContain("Moon Party");

            var again = postMultipart(browser, "/admin/games/import", Map.of("ownCopy", "yes"), "moon-party.pdf", pdf);
            assertThat(again.statusCode()).isEqualTo(200);
            assertThat(again.body()).contains("already exists");
            var unconfirmed = postMultipart(browser, "/admin/games/import", Map.of("name", "Other Game"), "moon-party.pdf", pdf);
            assertThat(unconfirmed.body()).contains("Confirm that this is your own copy");
            var notPdf = postMultipart(browser, "/admin/games/import", Map.of("ownCopy", "yes", "name", "Third"), "rules.pdf",
                    "plain text".getBytes(StandardCharsets.UTF_8));
            assertThat(notPdf.body()).contains("could not be read as a PDF");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM games WHERE slug IN ('other-game', 'third')", Integer.class)).isZero();

            // Replace the rulebook: upload a second copy, give the first one a vector, then remove the first one.
            assertThat(page.body()).contains("Remove source", "name=\"confirm\"");
            UUID first = jdbc.queryForObject("SELECT id FROM rule_documents WHERE game_version_id = ?", UUID.class, version);
            assertThat(postMultipart(browser, "/admin/games/" + gameId + "/rules", Map.of("name", "Clean copy", "sourceType", "RULEBOOK"),
                    "moon-party.pdf", pdf).statusCode()).isEqualTo(302);
            UUID chunk = jdbc.queryForObject("SELECT id FROM rule_chunks WHERE document_id = ? ORDER BY chunk_index LIMIT 1", UUID.class, first);
            jdbc.update("INSERT INTO rule_chunk_embeddings (chunk_id, game_version_id, provider, model, dimensions, embedding) "
                    + "VALUES (?, ?, 'fixture', 'fixture-model', 3, '[1,0,0]')", chunk, version);

            assertThat(postForm(browser, "/admin/games/" + gameId + "/rules/" + first + "/remove", "").statusCode()).isEqualTo(302);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_documents WHERE id = ?", Integer.class, first))
                    .as("removal needs the confirmation tick").isOne();

            assertThat(postForm(browser, "/admin/games/" + gameId + "/rules/" + first + "/remove", "confirm=yes").statusCode()).isEqualTo(302);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_documents WHERE id = ?", Integer.class, first)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_chunks WHERE document_id = ?", Integer.class, first)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_chunk_embeddings WHERE chunk_id = ?", Integer.class, chunk)).isZero();
            assertThat(jdbc.queryForList("SELECT name FROM rule_documents WHERE game_version_id = ?", String.class, version))
                    .containsExactly("Clean copy");
            assertThat(postForm(browser, "/admin/games/" + gameId + "/rules/" + first + "/remove", "confirm=yes").statusCode())
                    .as("removing twice is a friendly error, not a crash").isEqualTo(302);
        }
    }

    private HttpResponse<String> postForm(HttpClient browser, String path, String body) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return get(browser, path, List.of());
    }
    private HttpResponse<String> get(HttpClient browser, String path, List<String> cookies) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : "http://localhost:" + port + path)).GET();
        for (var cookie : cookies) { builder.header("Cookie", cookie.split(";", 2)[0]); }
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postMultipart(HttpClient browser, String path, Map<String, String> fields, String fileName, byte[] file)
            throws Exception {
        String boundary = "----bgm" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(file);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
