package com.boardgamemaster.rules;

import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.*;
import com.boardgamemaster.rules.infrastructure.RuleChunkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "boardgame.rules.chunk-size=96", "boardgame.rules.chunk-overlap=24",
    "boardgame.rules.max-bytes=4096", "spring.servlet.multipart.max-file-size=5KB", "spring.servlet.multipart.max-request-size=6KB"})
class RuleIngestionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final String BASE = "/api/v1/game-versions/" + VERSION + "/documents";
    static final String METADATA = """
        {"name":"Original demo","sourceType":"RULEBOOK","version":"1","language":"en","sourceReference":"Original test text"}
        """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleDocumentService service;
    @MockitoSpyBean RuleChunkRepository chunks;
    @LocalServerPort int port;

    private MockMultipartFile metadata(String json) {
        return new MockMultipartFile("metadata", "metadata.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }
    private MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }
    private JsonNode importText(String text) throws Exception {
        var response = mvc.perform(multipart(BASE).file(metadata(METADATA)).file(file("demo.md", text)))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString());
    }
    private DocumentMetadata domainMetadata() { return new DocumentMetadata("Demo", RuleSourceType.FAQ, "1", "en", "Original test text"); }

    @Test
    void importsAndReadsOrderedTraceableChunksAndSource() throws Exception {
        String text = "# Setup\r\n\r\nPlace one token.\r\n\r\n<!-- page: 2 -->\r\n## Turns\r\n\r\n" +
                "Move to an empty space. ".repeat(12);
        var document = importText(text);
        String path = BASE + "/" + document.path("id").asText();
        assertThat(document.path("gameVersionId").asText()).isEqualTo(VERSION.toString());
        assertThat(document.path("chunkSize").asInt()).isEqualTo(96);
        assertThat(document.path("chunkCount").asInt()).isGreaterThan(2);
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Original demo"));
        var source = mapper.readTree(mvc.perform(get(path + "/source")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("text").asText();
        var response = mapper.readTree(mvc.perform(get(path + "/chunks?size=100")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var items = response.path("items");
        assertThat(items.size()).isEqualTo(document.path("chunkCount").asInt());
        for (int i = 0; i < items.size(); i++) {
            var c = items.get(i);
            assertThat(c.path("chunkIndex").asInt()).isEqualTo(i);
            assertThat(c.path("documentId").asText()).isEqualTo(document.path("id").asText());
            assertThat(c.path("gameVersionId").asText()).isEqualTo(VERSION.toString());
            assertThat(c.path("content").asText()).isEqualTo(source.substring(c.path("startOffset").asInt(), c.path("endOffset").asInt()));
        }
        assertThat(items.get(0).path("sectionTitle").asText()).isEqualTo("Setup");
        assertThat(items.get(items.size() - 1).path("pageNumber").asInt()).isEqualTo(2);
        mvc.perform(get(path + "/chunks?size=1&page=1")).andExpect(jsonPath("$.items[0].chunkIndex").value(1));
        mvc.perform(get(BASE + "?size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/game-versions/{versionId}/documents'].post").exists());
    }

    @Test
    void isolatesVersionsIncludingAnotherGameAndSameGameEdition() throws Exception {
        var document = importText("Original content.");
        UUID otherGame = UUID.randomUUID();
        UUID otherVersion = UUID.randomUUID();
        UUID sameGameVersion = UUID.randomUUID();
        jdbc.update("INSERT INTO games (id,slug,name,min_players,max_players,estimated_duration_minutes,active) VALUES (?,?,?,1,2,15,true)", otherGame, otherGame.toString(), "Other");
        jdbc.update("INSERT INTO game_versions (id,game_id,edition,version_label,language,active,default_version) VALUES (?,?, 'Demo','1','en',true,false)", otherVersion, otherGame);
        jdbc.update("INSERT INTO game_versions (id,game_id,edition,version_label,language,active,default_version) VALUES (?,?, 'Other edition','1','en',true,false)", sameGameVersion, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        for (UUID version : new UUID[]{otherVersion, sameGameVersion}) {
            String base = "/api/v1/game-versions/" + version + "/documents";
            mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
            for (String suffix : new String[]{"", "/chunks", "/source"}) {
                mvc.perform(get(base + "/" + document.path("id").asText() + suffix)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
            }
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE rule_chunks SET game_version_id=? WHERE document_id=?", otherVersion,
                UUID.fromString(document.path("id").asText()))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void malformedImportsHaveSafeErrorsAndStoreNothing() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM rule_documents", Long.class);
        mvc.perform(multipart(BASE).file(metadata(METADATA)).file(file("book.pdf", "text")))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        mvc.perform(multipart(BASE).file(metadata(METADATA)).file(file("empty.txt", " \n")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));
        mvc.perform(multipart(BASE).file(metadata(METADATA)).file(new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{0})))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(BASE).file(metadata(METADATA.replace("\"en\"", "\"id\""))).file(file("demo.txt", "text")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Document language must match the selected game version"));
        mvc.perform(multipart(BASE).file(metadata("{\"name\":\"x\"}")).file(file("demo.txt", "text")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(multipart(BASE).file(metadata("{bad json")).file(file("demo.txt", "text")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(BASE).file(metadata(METADATA)).file(file("large.txt", "x".repeat(4097))))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("DOCUMENT_TOO_LARGE"));
        mvc.perform(get(BASE + "?size=101")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        mvc.perform(get("/api/v1/game-versions/" + UUID.randomUUID() + "/documents"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME_VERSION_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_documents", Long.class)).isEqualTo(before);
    }

    @Test
    void failedChunkWriteRollsBackWholeDocument() {
        long before = jdbc.queryForObject("SELECT count(*) FROM rule_documents", Long.class);
        doThrow(new DataIntegrityViolationException("Simulated storage failure")).when(chunks).save(any(RuleChunk.class));
        assertThatThrownBy(() -> service.ingest(VERSION, domainMetadata(), "demo.txt", "Original text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_documents", Long.class)).isEqualTo(before);
    }

    @Test
    void realHttpAcceptsMultipartAndRejectsServletOversize() throws Exception {
        String boundary = "rule-test-boundary";
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n" + METADATA
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.txt\"\r\nContent-Type: text/plain\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";
        try (var client = HttpClient.newHttpClient()) {
            var uploaded = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(prefix + "Original HTTP source." + suffix)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(uploaded.statusCode()).isEqualTo(201);
            String location = uploaded.headers().firstValue("Location").orElseThrow();
            var read = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + location + "/chunks")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(read.body()).path("items").get(0).path("content").asText()).isEqualTo("Original HTTP source.");
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(prefix + "x".repeat(8000) + suffix)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
            assertThat(response.body()).doesNotContain("trace", "Exception");
        }
    }

    @Test
    void databaseEnforcesDocumentOwnershipChunkOrderingAndNonEmptyContent() {
        var document = service.ingest(VERSION, domainMetadata(), "demo.txt", "Original constraint fixture.".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> jdbc.update("UPDATE rule_documents SET game_version_id=? WHERE id=?", UUID.randomUUID(), document.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE rule_chunks SET chunk_index=-1 WHERE document_id=?", document.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE rule_chunks SET content='' WHERE document_id=?", document.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE rule_chunks SET end_offset=start_offset WHERE document_id=?", document.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO rule_chunks (id,document_id,game_version_id,content,chunk_index,start_offset,end_offset) SELECT ?,document_id,game_version_id,content,chunk_index,start_offset,end_offset FROM rule_chunks WHERE document_id=?",
                UUID.randomUUID(), document.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradesExistingV3CatalogWithoutChangingItsData() {
        jdbc.execute("CREATE DATABASE rule_upgrade");
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/rule_upgrade");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("3").load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        db.update("UPDATE games SET description='Preserved user metadata' WHERE slug='catan'");
        var upgrade = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(db.queryForObject("SELECT description FROM games WHERE slug='catan'", String.class)).isEqualTo("Preserved user metadata");
        assertThat(db.queryForObject("SELECT count(*) FROM rule_documents", Long.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
