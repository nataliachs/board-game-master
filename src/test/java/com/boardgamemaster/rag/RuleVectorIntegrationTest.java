package com.boardgamemaster.rag;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "boardgame.embedding.dimensions=3")
class RuleVectorIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @MockitoBean EmbeddingClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleDocumentService documents;
    @Autowired RuleEmbeddingService indexing;
    @Autowired RuleRetrievalService retrieval;
    @Autowired RuleVectorStore store;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final EmbeddingProfile profile = new EmbeddingProfile("fixture", "known-vectors", 3);

    @BeforeEach void provider() {
        when(client.profile()).thenReturn(profile);
        when(client.embed(anyString())).thenAnswer(call -> vector(call.getArgument(0)));
    }
    private Embedding vector(String text) {
        if (text.contains("alpha")) { return new Embedding(List.of(1f, 0f, 0f)); }
        if (text.contains("near")) { return new Embedding(List.of(.8f, .6f, 0f)); }
        if (text.contains("opposite")) { return new Embedding(List.of(-1f, 0f, 0f)); }
        return new Embedding(List.of(0f, 1f, 0f));
    }
    private UUID game() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO games (id,slug,name,min_players,max_players,estimated_duration_minutes,active) VALUES (?,?,?,1,4,15,true)", id, "fixture-" + id, "Original fixture game");
        return id;
    }
    private UUID version(UUID game) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO game_versions (id,game_id,edition,version_label,language,active,default_version) VALUES (?,?,?,'base','en',true,false)", id, game, "Fixture " + id);
        return id;
    }
    private RuleDocument document(UUID version, String text) {
        return documents.ingest(version, new DocumentMetadata("Original fixture", RuleSourceType.FAQ, "1", "en", "Original test content"),
                "fixture.md", text.getBytes(StandardCharsets.UTF_8));
    }
    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (method.equals("GET")) { builder.GET(); }
            else { builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)); }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void ranksKnownVectorsAndReturnsTraceableMetadataWithoutAnLlm() {
        UUID version = version(game());
        var document = document(version, "# Alpha\n\nalpha rule.\n\n<!-- page: 2 -->\n# Near\n\nnear rule.\n\n# Beta\n\nbeta rule.\n\n# Opposite\n\nopposite rule.");
        var indexed = indexing.index(version, document.getId());
        assertThat(indexed.totalChunks()).isEqualTo(4);
        assertThat(indexed.newlyIndexedChunks()).isEqualTo(4);
        var results = retrieval.search(version, "alpha", 3, -1d, null).matches();
        assertThat(results).hasSize(3);
        assertThat(results).extracting(RuleVectorStore.Match::sectionTitle).containsExactly("Alpha", "Near", "Beta");
        assertThat(results.get(0).cosineSimilarity()).isCloseTo(1, within(.000001));
        assertThat(results.get(1).cosineSimilarity()).isCloseTo(.8, within(.000001));
        assertThat(results.get(2).cosineSimilarity()).isCloseTo(0, within(.000001));
        assertThat(results.get(1).pageNumber()).isEqualTo(2);
        assertThat(results).allSatisfy(match -> {
            assertThat(match.documentId()).isEqualTo(document.getId());
            assertThat(match.gameVersionId()).isEqualTo(version);
            assertThat(match.sourceReference()).isEqualTo("Original test content");
            assertThat(match.content()).isEqualTo(document.getNormalizedText().substring(match.startOffset(), match.endOffset()));
        });
    }

    @Test void excludesOtherGamesEditionsModelsProvidersAndDimensions() {
        UUID game = game();
        UUID chosen = version(game);
        UUID edition = version(game);
        UUID otherGame = version(game());
        var target = document(chosen, "beta target rule.");
        indexing.index(chosen, target.getId());
        for (UUID other : List.of(edition, otherGame)) { indexing.index(other, document(other, "alpha unrelated rule.").getId()); }
        UUID chunk = store.pending(chosen, target.getId(), new EmbeddingProfile("other", "model", 3)).getFirst().id();
        store.store(chosen, target.getId(), new EmbeddingProfile("other", "known-vectors", 3), List.of(new RuleVectorStore.VectorWrite(chunk, vector("alpha"))));
        store.store(chosen, target.getId(), new EmbeddingProfile("fixture", "other-model", 3), List.of(new RuleVectorStore.VectorWrite(chunk, vector("alpha"))));
        store.store(chosen, target.getId(), new EmbeddingProfile("fixture", "known-vectors", 2), List.of(new RuleVectorStore.VectorWrite(chunk, new Embedding(List.of(1f, 0f)))));
        assertThat(retrieval.search(chosen, "alpha", 50, -1d, null).matches()).hasSize(1).allSatisfy(m -> {
            assertThat(m.documentId()).isEqualTo(target.getId());
            assertThat(m.gameVersionId()).isEqualTo(chosen);
            assertThat(m.cosineSimilarity()).isCloseTo(0, within(.000001));
        });
    }

    @Test void indexingIsIdempotentAndSearchTiesAreStable() {
        UUID version = version(game());
        var document = document(version, "# First\n\nalpha rule.\n\n# Second\n\nalpha rule.");
        indexing.index(version, document.getId());
        clearInvocations(client);
        assertThat(indexing.index(version, document.getId()).newlyIndexedChunks()).isZero();
        verify(client, never()).embed(anyString());
        assertThat(indexing.status(version, document.getId()).indexedChunks()).isEqualTo(2);
        var first = retrieval.search(version, "alpha", null).matches();
        assertThat(first).extracting(RuleVectorStore.Match::chunkIndex).containsExactly(0, 1);
        assertThat(retrieval.search(version, "alpha", null).matches()).isEqualTo(first);
    }

    @Test void failedProviderLeavesDocumentUnindexedAndEarlierIndexUsable() {
        UUID version = version(game());
        var existing = document(version, "alpha existing rule.");
        indexing.index(version, existing.getId());
        var failed = document(version, "# First\n\nalpha new rule.\n\n# Second\n\nbeta failed rule.");
        when(client.embed(contains("failed"))).thenThrow(new EmbeddingException(EmbeddingException.Code.EMBEDDING_PROVIDER_UNAVAILABLE, "Embedding provider unavailable"));
        assertThatThrownBy(() -> indexing.index(version, failed.getId())).isInstanceOf(EmbeddingException.class);
        assertThat(indexing.status(version, failed.getId()).indexedChunks()).isZero();
        assertThat(retrieval.search(version, "alpha", 5).matches()).hasSize(1).allSatisfy(m -> assertThat(m.documentId()).isEqualTo(existing.getId()));
    }

    @Test void databaseWriteFailureRollsBackAllVectors() {
        UUID version = version(game());
        var document = document(version, "# First\n\nalpha.\n\n# Second\n\nbeta.");
        UUID first = store.pending(version, document.getId(), profile).getFirst().id();
        assertThatThrownBy(() -> store.store(version, document.getId(), profile, List.of(
                new RuleVectorStore.VectorWrite(first, vector("alpha")),
                new RuleVectorStore.VectorWrite(UUID.randomUUID(), vector("beta")))))
                .isInstanceOf(RetrievalException.class);
        assertThat(indexing.status(version, document.getId()).indexedChunks()).isZero();
    }

    @Test void databaseRejectsCrossVersionZeroAndDimensionMismatch() {
        UUID version = version(game());
        UUID other = version(game());
        var document = document(version, "alpha rule.");
        UUID chunk = store.pending(version, document.getId(), profile).getFirst().id();
        String sql = "INSERT INTO rule_chunk_embeddings (chunk_id,game_version_id,provider,model,dimensions,embedding) VALUES (?,?,'fixture','db-check',?,CAST(? AS vector))";
        assertThatThrownBy(() -> jdbc.update(sql, chunk, other, 3, "[1,0,0]")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, chunk, version, 3, "[0,0,0]")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, chunk, version, 3, "[1,0]")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void concurrentIndexingRetainsOneVectorPerChunk() throws Exception {
        UUID version = version(game());
        var document = document(version, "# First\n\nalpha.\n\n# Second\n\nbeta.");
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(client.embed(anyString())).thenAnswer(call -> {
            ready.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Concurrent embedding timed out"); }
            return vector(call.getArgument(0));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> indexing.index(version, document.getId()));
            var second = executor.submit(() -> indexing.index(version, document.getId()));
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
            assertThat(first.get(20, TimeUnit.SECONDS).newlyIndexedChunks() + second.get(20, TimeUnit.SECONDS).newlyIndexedChunks()).isEqualTo(2);
        }
        assertThat(indexing.status(version, document.getId()).indexedChunks()).isEqualTo(2);
    }

    @Test void httpIndexSearchStatusAndSafeFailures() throws Exception {
        UUID version = version(game());
        UUID other = version(game());
        var document = document(version, "alpha rule.");
        String base = "/api/v1/game-versions/" + version;
        String indexPath = base + "/documents/" + document.getId() + "/embeddings";
        assertThat(request("GET", indexPath, "").statusCode()).isEqualTo(200);
        var indexed = request("POST", indexPath, "");
        assertThat(indexed.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(indexed.body()).path("indexedChunks").asInt()).isEqualTo(1);
        var found = request("POST", base + "/rules/search", "{\"query\":\"alpha\",\"topK\":1}");
        assertThat(found.statusCode()).isEqualTo(200);
        var result = mapper.readTree(found.body());
        assertThat(result.path("matches").get(0).path("documentId").asText()).isEqualTo(document.getId().toString());
        assertThat(result.has("answer")).isFalse();
        assertThat(result.has("confidence")).isFalse();
        assertThat(request("POST", base + "/rules/search", "{\"query\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(request("POST", base + "/rules/search", "{\"query\":\"alpha\",\"topK\":51}").statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/v1/game-versions/" + other + "/documents/" + document.getId() + "/embeddings", "").statusCode()).isEqualTo(404);
        when(client.embed("alpha")).thenThrow(new EmbeddingException(EmbeddingException.Code.EMBEDDING_TIMEOUT, "Embedding provider request timed out"));
        var failure = request("POST", base + "/rules/search", "{\"query\":\"alpha\"}");
        assertThat(failure.statusCode()).isEqualTo(504);
        assertThat(mapper.readTree(failure.body()).path("code").asText()).isEqualTo("EMBEDDING_TIMEOUT");
        assertThat(failure.body()).doesNotContain("trace", "Exception", "apiKey");
        var docs = request("GET", "/v3/api-docs", "");
        assertThat(docs.body()).contains("/api/v1/game-versions/{versionId}/rules/search");
    }

    @Test void emptyIndexAvoidsPaidQueryAndDisabledProviderFailsClearly() throws Exception {
        UUID version = version(game());
        assertThat(retrieval.search(version, "alpha", 5).matches()).isEmpty();
        verify(client, never()).embed(anyString());
        when(client.profile()).thenReturn(new EmbeddingProfile("disabled", "known-vectors", 3));
        var result = request("POST", "/api/v1/game-versions/" + version + "/rules/search", "{\"query\":\"alpha\"}");
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(mapper.readTree(result.body()).path("code").asText()).isEqualTo("EMBEDDING_NOT_CONFIGURED");
    }

    @Test void upgradesV4WithExistingDocumentsAndChunksWithoutGeneratingVectors() {
        jdbc.execute("CREATE DATABASE embedding_upgrade");
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/embedding_upgrade");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("4").load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID document = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        UUID version = UUID.fromString("00000000-0000-0000-0000-000000000002");
        db.update("""
            INSERT INTO rule_documents (id,game_version_id,name,source_type,version,language,source_reference,file_name,imported_at,normalized_text,chunk_size,chunk_overlap,chunk_count)
            VALUES (?,?,'Existing original source','FAQ','1','en','Own fixture','own.txt',CURRENT_TIMESTAMP,'Preserved text',64,0,1)
            """, document, version);
        db.update("INSERT INTO rule_chunks (id,document_id,game_version_id,content,chunk_index,start_offset,end_offset) VALUES (?,?,?,'Preserved text',0,0,14)", chunk, document, version);
        // V9 intentionally replaces the Catan reference seed, so this check stops at V8.
        var upgrade = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("8").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
        upgrade.validate();
        assertThat(db.queryForObject("SELECT content FROM rule_chunks WHERE id=?", String.class, chunk)).isEqualTo("Preserved text");
        assertThat(db.queryForObject("SELECT count(*) FROM rule_chunk_embeddings", Integer.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='rule_embeddings_by_version_profile'", Integer.class)).isEqualTo(1);
    }
}
