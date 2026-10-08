package com.boardgamemaster.game;

import static org.assertj.core.api.Assertions.*;

import com.boardgamemaster.game.application.GameService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
class GameCatalogIntegrationTest {
    private static final String CATAN = "00000000-0000-0000-0000-000000000001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired GameService service;

    @Test
    void referenceGameHasOneDefaultAndOpenApiDescribesCatalog() throws Exception {
        var game = json(request("GET", "/api/v1/games/" + CATAN, null), 200);
        assertThat(game.path("slug").asText()).isEqualTo("catan");
        var version = json(request("GET", "/api/v1/games/" + CATAN + "/default-version?playerCount=4", null), 200);
        assertThat(version.path("edition").asText()).isEqualTo("5th Edition");
        assertThat(version.path("language").asText()).isEqualTo("en");
        assertThat(version.path("defaultVersion").asBoolean()).isTrue();
        assertThat(json(request("GET", "/api/v1/games/" + CATAN + "/versions", null), 200)).hasSize(1);
        var list = json(request("GET", "/api/v1/games?size=1", null), 200);
        assertThat(list.path("items")).hasSize(1);
        assertThat(list.path("size").asInt()).isEqualTo(1);
        var docs = json(request("GET", "/v3/api-docs", null), 200);
        assertThat(docs.path("paths").has("/api/v1/games/{gameId}/default-version")).isTrue();
    }

    @Test
    void createsAndReadsGenericGameAndMultipleVersions() throws Exception {
        var create = request("POST", "/api/v1/games", gamePayload());
        var game = json(create, 201);
        String path = create.headers().firstValue("Location").orElseThrow();
        assertThat(json(request("GET", path, null), 200)).isEqualTo(game);
        var first = createVersion(game.path("id").asText(), "First", true, true);
        assertThat(first.path("gameId").asText()).isEqualTo(game.path("id").asText());
        assertThat(first.path("language").asText()).isEqualTo("en-us");
        var second = createVersion(game.path("id").asText(), "Second", true, false);
        assertThat(second.path("defaultVersion").asBoolean()).isFalse();
        assertThat(json(request("GET", path + "/versions", null), 200)).hasSize(2);
        assertThat(json(request("GET", path + "/versions/" + first.path("id").asText(), null), 200)).isEqualTo(first);
        assertThat(json(request("GET", path + "/default-version?playerCount=2", null), 200).path("id"))
                .isEqualTo(first.path("id"));
    }

    @Test
    void defaultSwitchIsAtomicIdempotentAndNewDefaultCreationReplacesOld() throws Exception {
        String id = createGame();
        var first = createVersion(id, "First", true, true);
        var second = createVersion(id, "Second", true, false);
        String path = "/api/v1/games/" + id;
        var body = Map.of("gameVersionId", second.path("id").asText());
        assertThat(json(request("PUT", path + "/default-version", body), 200).path("defaultVersion").asBoolean()).isTrue();
        json(request("PUT", path + "/default-version", body), 200);
        assertThat(json(request("GET", path + "/versions/" + first.path("id").asText(), null), 200)
                .path("defaultVersion").asBoolean()).isFalse();
        var third = createVersion(id, "Third", true, true);
        assertThat(json(request("GET", path + "/default-version", null), 200).path("id")).isEqualTo(third.path("id"));
        assertThat(defaultCount(id)).isEqualTo(1);
    }

    @Test
    void rejectsForeignVersionWithoutChangingEitherGameDefault() throws Exception {
        String owner = createGame();
        String other = createGame();
        var foreign = createVersion(owner, "First", true, true);
        var own = createVersion(other, "First", true, true);
        String path = "/api/v1/games/" + other;
        error(request("GET", path + "/versions/" + foreign.path("id").asText(), null), 404, "GAME_VERSION_NOT_FOUND");
        error(request("PUT", path + "/default-version", Map.of("gameVersionId", foreign.path("id").asText())),
                404, "GAME_VERSION_NOT_FOUND");
        assertThat(json(request("GET", path + "/default-version", null), 200).path("id")).isEqualTo(own.path("id"));
        assertThat(defaultCount(owner)).isEqualTo(1);
    }

    @Test
    void invalidPlayerCountAndUnavailableDefaultsFailClearly() throws Exception {
        error(request("GET", "/api/v1/games/" + CATAN + "/default-version?playerCount=2", null), 400, "INVALID_PLAYER_COUNT");
        error(request("GET", "/api/v1/games/" + CATAN + "/default-version?playerCount=5", null), 400, "INVALID_PLAYER_COUNT");
        String noDefault = createGame();
        error(request("GET", "/api/v1/games/" + noDefault + "/default-version", null), 409, "DEFAULT_VERSION_NOT_CONFIGURED");
        var payload = gamePayload();
        payload.put("active", false);
        String inactive = json(request("POST", "/api/v1/games", payload), 201).path("id").asText();
        error(request("GET", "/api/v1/games/" + inactive + "/default-version", null), 409, "GAME_INACTIVE");
        var first = createVersion(noDefault, "First", true, true);
        var version = createVersion(noDefault, "Inactive", false, false);
        error(request("PUT", "/api/v1/games/" + noDefault + "/default-version", Map.of("gameVersionId", version.path("id").asText())),
                409, "GAME_VERSION_INACTIVE");
        error(request("POST", "/api/v1/games/" + noDefault + "/versions", versionPayload("Invalid", false, true)),
                409, "GAME_VERSION_INACTIVE");
        assertThat(json(request("GET", "/api/v1/games/" + noDefault + "/default-version", null), 200).path("id"))
                .isEqualTo(first.path("id"));
    }

    @Test
    void validatesBodyTypesRequiredFieldsAndMetadata() throws Exception {
        error(request("POST", "/api/v1/games", Map.of()), 400, "INVALID_REQUEST");
        var payload = gamePayload();
        payload.put("minPlayers", 5);
        error(request("POST", "/api/v1/games", payload), 400, "INVALID_GAME");
        payload.put("minPlayers", 0);
        error(request("POST", "/api/v1/games", payload), 400, "INVALID_REQUEST");
        payload = gamePayload();
        payload.put("name", " ");
        error(request("POST", "/api/v1/games", payload), 400, "INVALID_REQUEST");
        payload = gamePayload();
        payload.put("slug", "INVALID SLUG");
        error(request("POST", "/api/v1/games", payload), 400, "INVALID_REQUEST");
        error(request("GET", "/api/v1/games/not-a-uuid", null), 400, "INVALID_REQUEST");
        error(request("GET", "/api/v1/games?page=-1", null), 400, "INVALID_PAGINATION");
        error(request("GET", "/api/v1/games?size=101", null), 400, "INVALID_PAGINATION");
        error(request("PUT", "/api/v1/games/" + CATAN + "/default-version", Map.of()), 400, "INVALID_REQUEST");
        var version = versionPayload("First", true, false);
        version.put("releaseYear", 0);
        error(request("POST", "/api/v1/games/" + CATAN + "/versions", version), 400, "INVALID_REQUEST");
        version.put("releaseYear", 2020);
        version.put("language", "English");
        error(request("POST", "/api/v1/games/" + CATAN + "/versions", version), 400, "INVALID_REQUEST");
        error(request("GET", "/api/v1/games/" + UUID.randomUUID(), null), 404, "GAME_NOT_FOUND");
        error(request("POST", "/api/v1/games/" + UUID.randomUUID() + "/versions", versionPayload("First", true, false)),
                404, "GAME_NOT_FOUND");
    }

    @Test
    void duplicateRegistrationsReturnConflicts() throws Exception {
        var payload = gamePayload();
        String id = json(request("POST", "/api/v1/games", payload), 201).path("id").asText();
        error(request("POST", "/api/v1/games", payload), 409, "GAME_SLUG_EXISTS");
        createVersion(id, "First", true, true);
        var duplicate = versionPayload(" FIRST ", true, true);
        duplicate.put("versionLabel", "BASE");
        duplicate.put("language", "en-US");
        error(request("POST", "/api/v1/games/" + id + "/versions", duplicate), 409, "GAME_VERSION_EXISTS");
        assertThat(defaultCount(id)).isEqualTo(1);
    }

    @Test
    void concurrentDefaultSelectionsRetainExactlyOneDefault() throws Exception {
        String id = createGame();
        UUID first = UUID.fromString(createVersion(id, "First", true, true).path("id").asText());
        UUID second = UUID.fromString(createVersion(id, "Second", true, false).path("id").asText());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return service.setDefaultVersion(UUID.fromString(id), first).getId(); });
            var b = executor.submit(() -> { start.await(); return service.setDefaultVersion(UUID.fromString(id), second).getId(); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(second);
        }
        assertThat(defaultCount(id)).isEqualTo(1);
        assertThat(service.resolveDefaultVersion(UUID.fromString(id), null).getId()).isIn(first, second);
    }

    @Test
    void databaseEnforcesOwnershipAndDefaultConstraints() throws Exception {
        UUID id = UUID.fromString(createGame());
        createVersion(id.toString(), "First", true, true);
        assertThatThrownBy(() -> insertVersion(id, "Second", true, true)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertVersion(id, "Inactive", false, true)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertVersion(UUID.randomUUID(), "Orphan", true, false)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertVersion(id, "FIRST", true, false)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE games SET min_players = 0 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(defaultCount(id.toString())).isEqualTo(1);
    }

    @Test
    void upgradesMilestoneZeroDatabaseWithoutRemovingExistingData() {
        jdbc.execute("CREATE DATABASE catalog_upgrade");
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/catalog_upgrade");
        var initial = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("1").load();
        assertThat(initial.migrate().migrationsExecuted).isEqualTo(1);
        var upgradeJdbc = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        upgradeJdbc.execute("CREATE TABLE bootstrap_marker (value TEXT NOT NULL)");
        upgradeJdbc.update("INSERT INTO bootstrap_marker VALUES ('preserved')");
        var upgrade = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(5);
        upgrade.validate();
        assertThat(upgradeJdbc.queryForObject("SELECT value FROM bootstrap_marker", String.class)).isEqualTo("preserved");
        assertThat(upgradeJdbc.queryForObject("SELECT count(*) FROM games WHERE slug = 'catan'", Integer.class)).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    private void insertVersion(UUID gameId, String edition, boolean active, boolean defaultVersion) {
        jdbc.update("INSERT INTO game_versions (id, game_id, edition, version_label, language, active, default_version) VALUES (?, ?, ?, 'base', 'en-us', ?, ?)",
                UUID.randomUUID(), gameId, edition, active, defaultVersion);
    }

    private int defaultCount(String gameId) {
        return jdbc.queryForObject("SELECT count(*) FROM game_versions WHERE game_id = ? AND default_version", Integer.class, UUID.fromString(gameId));
    }

    private Map<String, Object> gamePayload() {
        var payload = new HashMap<String, Object>();
        payload.put("slug", "demo-" + UUID.randomUUID());
        payload.put("name", "Demo Game");
        payload.put("minPlayers", 2);
        payload.put("maxPlayers", 4);
        payload.put("estimatedDurationMinutes", 30);
        payload.put("active", true);
        return payload;
    }

    private Map<String, Object> versionPayload(String edition, boolean active, boolean defaultVersion) {
        var payload = new HashMap<String, Object>();
        payload.put("edition", edition);
        payload.put("versionLabel", "base");
        payload.put("language", "EN-us");
        payload.put("active", active);
        payload.put("defaultVersion", defaultVersion);
        return payload;
    }

    private String createGame() throws Exception {
        return json(request("POST", "/api/v1/games", gamePayload()), 201).path("id").asText();
    }

    private JsonNode createVersion(String gameId, String edition, boolean active, boolean defaultVersion) throws Exception {
        return json(request("POST", "/api/v1/games/" + gameId + "/versions", versionPayload(edition, active, defaultVersion)), 201);
    }

    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode json(HttpResponse<String> response, int expectedStatus) throws Exception {
        assertThat(response.statusCode()).withFailMessage("Expected %s, got %s: %s", expectedStatus, response.statusCode(), response.body())
                .isEqualTo(expectedStatus);
        return mapper.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) throws Exception {
        var error = json(response, status);
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.path("timestamp").asText()).isNotBlank();
        assertThat(error.path("path").asText()).startsWith("/api/v1/");
        assertThat(error.has("trace")).isFalse();
    }
}
