package com.boardgamemaster.session;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.*;
import com.boardgamemaster.session.application.*;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.setup.application.SetupService;
import com.boardgamemaster.setup.domain.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "boardgame.embedding.dimensions=3", "boardgame.session.recent-message-limit=4", "boardgame.session.context-characters=1000"})
class SessionIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @MockitoBean EmbeddingClient embeddings;
    @MockitoBean LlmClient llm;
    @Autowired GameService games;
    @Autowired TeachingService teaching;
    @Autowired SetupService setups;
    @Autowired SessionService sessions;
    @Autowired SessionContextService contexts;
    @Autowired RuleDocumentService documents;
    @Autowired RuleEmbeddingService indexing;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private static final UUID CATAN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CATAN_VERSION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private record Fixture(UUID game, UUID version, TeachingFlow flow) { }

    @BeforeEach void providers() {
        when(embeddings.profile()).thenReturn(new EmbeddingProfile("fixture", "session-vectors", 3));
        when(embeddings.embed(anyString())).thenReturn(new Embedding(List.of(1f, 0f, 0f)));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var input = mapper.readTree(((LlmRequest) call.getArgument(0)).input());
            var excerpt = input.path("excerpts").get(0);
            String content = excerpt.path("content").asText();
            return new LlmResponse(content, true, List.of(new LlmResponse.Citation(UUID.fromString(excerpt.path("chunkId").asText()), content)));
        }).when(llm).generate(any());
    }
    private TeachingSection section(String key, int order, Set<TeachingDepth> depths) {
        return new TeachingSection(key, order, key + " lesson", "An original teaching explanation.", depths,
                List.of("A simple original example."), List.of("Use the printed reference."), List.of("Original fixture section"));
    }
    private Fixture fixture(boolean indexed) {
        UUID game = games.createGame("session-" + UUID.randomUUID(), "Original session game", null, 2, 4, 20, null, true).getId();
        UUID version = games.createVersion(game, "First", "base", "en", null, true, true).getId();
        var flow = teaching.publish(version, "Original lessons", List.of(section("START", 0, null),
                section("DETAIL", 1, Set.of(TeachingDepth.DETAILED)), section("END", 2, null)));
        if (indexed) { index(version, "Move one space to collect a resource."); }
        return new Fixture(game, version, flow);
    }
    private UUID index(UUID version, String content) {
        var doc = documents.ingest(version, new DocumentMetadata("Original session rules", RuleSourceType.RULEBOOK, "1", "en", "Original test text"),
                "rules.md", content.getBytes(StandardCharsets.UTF_8));
        indexing.index(version, doc.getId()); return doc.getId();
    }
    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (method.equals("GET")) { builder.GET(); }
            else { builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))); }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private JsonNode json(HttpResponse<String> response, int expected) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected); return mapper.readTree(response.body());
    }
    private JsonNode create(UUID game, String depth) throws Exception {
        var body = new HashMap<String, Object>(); body.put("gameId", game); body.put("playerCount", 3);
        if (depth != null) { body.put("teachingDepth", depth); }
        var response = request("POST", "/api/v1/sessions", body);
        assertThat(response.headers().firstValue("Location")).isPresent();
        return json(response, 201);
    }
    private String path(UUID id) { return "/api/v1/sessions/" + id; }

    @Test void createsTeachingFirstSessionDefaultDepthAndSurvivesFreshLoads() throws Exception {
        var f = fixture(false); var response = create(f.game(), null);
        UUID id = UUID.fromString(response.path("session").path("id").asText());
        var s = sessions.current(id);
        assertThat(s.session().gameVersionId()).isEqualTo(f.version());
        assertThat(s.session().teachingFlowId()).isEqualTo(f.flow().id());
        assertThat(s.session().teachingDepth()).isEqualTo(TeachingDepth.STANDARD);
        assertThat(s.session().stage()).isEqualTo(SessionStage.TEACHING);
        assertThat(s.lessonNumber()).isEqualTo(1); assertThat(s.totalLessons()).isEqualTo(2);
        assertThat(s.readyToPlay()).isFalse(); assertThat(s.notice()).contains("No guided setup is configured");
        assertThat(json(request("GET", path(id), null), 200).path("lesson").path("key").asText()).isEqualTo("START");
        verifyNoInteractions(llm); verify(embeddings, never()).embed(anyString());
    }
    @Test void referenceCatanSupportsEveryDepthWithoutProviders() {
        for (var depth : TeachingDepth.values()) {
            var s = sessions.create(CATAN, 4, depth);
            assertThat(s.session().gameVersionId()).isEqualTo(CATAN_VERSION);
            assertThat(s.lesson().key()).isEqualTo("INTRODUCTION");
            assertThat(s.totalLessons()).isEqualTo(depth == TeachingDepth.QUICK ? 8 : depth == TeachingDepth.STANDARD ? 10 : 11);
        }
        verifyNoInteractions(llm); verify(embeddings, never()).embed(anyString());
    }
    @Test void depthSpecificNavigationRepeatExplainAgainAndStaleActionProtection() throws Exception {
        var f = fixture(false); var s = sessions.create(f.game(), 3, TeachingDepth.DETAILED); UUID id = s.session().id();
        var next = json(request("POST", path(id) + "/teaching/actions", Map.of("action", "CONTINUE", "expectedRevision", 0)), 200);
        assertThat(next.path("lesson").path("key").asText()).isEqualTo("DETAIL");
        assertThat(json(request("POST", path(id) + "/teaching/actions", Map.of("action", "CONTINUE", "expectedRevision", 0)), 409).path("code").asText()).isEqualTo("SESSION_CONFLICT");
        var again = sessions.navigate(id, GameSession.Action.EXPLAIN_AGAIN, 1);
        assertThat(again.session().currentSectionKey()).isEqualTo("DETAIL");
        assertThat(again.explanation()).contains("Example: A simple original example.", "Tip:");
        var repeated = sessions.navigate(id, GameSession.Action.REPEAT, 2);
        assertThat(repeated.explanation()).isEqualTo(repeated.lesson().explanation());
        var previous = sessions.navigate(id, GameSession.Action.PREVIOUS, 3);
        assertThat(previous.session().currentSectionKey()).isEqualTo("START");
        assertThat(previous.session().completedSections()).containsExactly("START");
    }
    @Test void questionsAndContextualFollowupsPersistCitationsAndPreserveLesson() throws Exception {
        var f = fixture(true); var s = sessions.create(f.game(), 3, null); UUID id = s.session().id();
        var answer = json(request("POST", path(id) + "/messages", Map.of("question", "How do I collect a resource?")), 200);
        assertThat(answer.path("answer").path("confidence").asText()).isEqualTo("HIGH");
        assertThat(answer.path("answer").path("sources").size()).isEqualTo(1);
        var followup = sessions.ask(id, "How far do I move for that?");
        assertThat(followup.currentSectionKey()).isEqualTo("START");
        assertThat(sessions.current(id).session().completedSections()).isEmpty();
        verify(embeddings).embed(argThat(q -> q.startsWith("How far do I move for that?") && q.contains("How do I collect a resource?")));
        verify(llm).generate(argThat(r -> r.input().contains("How far do I move for that?") && r.input().contains("How do I collect a resource?")
                && r.input().contains(id.toString()) && r.input().contains("STANDARD") && r.input().contains("START lesson")));
        var history = json(request("GET", path(id) + "/messages", null), 200);
        assertThat(history.size()).isEqualTo(4);
        assertThat(history.get(0).path("role").asText()).isEqualTo("USER");
        assertThat(history.get(1).path("answer").path("sources").size()).isEqualTo(1);
        assertThat(history.get(3).path("sequenceNumber").asLong()).isEqualTo(4);
        var next = sessions.navigate(id, GameSession.Action.CONTINUE, followup.revision());
        assertThat(next.lesson().key()).isEqualTo("END");
        assertThat(next.session().completedSections()).containsExactly("START");
    }
    @Test void catalogDefaultAndNewFlowPublicationNeverRepinExistingSession() {
        var f = fixture(true); UUID id = sessions.create(f.game(), 3, null).session().id();
        var secondFlow = teaching.publish(f.version(), "Replacement", List.of(section("NEW", 0, null)));
        assertThat(sessions.current(id).session().teachingFlowId()).isEqualTo(f.flow().id());
        assertThat(sessions.create(f.game(), 3, null).session().teachingFlowId()).isEqualTo(secondFlow.id());
        var otherVersion = games.createVersion(f.game(), "Second", "base", "en", null, true, true).getId();
        UUID decoy = index(otherVersion, "Move ninety spaces.");
        var answer = sessions.ask(id, "How do I move?").answer();
        assertThat(answer.gameVersionId()).isEqualTo(f.version());
        assertThat(answer.sources()).noneMatch(source -> source.documentId().equals(decoy));
        verify(llm).generate(argThat(r -> !r.input().contains("ninety")));
        assertThat(sessions.current(id).session().gameVersionId()).isEqualTo(f.version());
    }
    @Test void emptyAndWeakEvidenceFallbackPersistWithoutMovingOrCallingLlm() {
        var f = fixture(false); UUID id = sessions.create(f.game(), 3, null).session().id();
        var result = sessions.ask(id, "Can I move?");
        assertThat(result.answer().confidence()).isEqualTo(RuleAnswer.Confidence.LOW);
        assertThat(sessions.history(id, null, 100)).hasSize(2);
        index(f.version(), "Move one space.");
        when(embeddings.embed(anyString())).thenReturn(new Embedding(List.of(0f, 1f, 0f)));
        assertThat(sessions.ask(id, "What about now?").answer().confidence()).isEqualTo(RuleAnswer.Confidence.LOW);
        assertThat(sessions.current(id).lesson().key()).isEqualTo("START");
        verifyNoInteractions(llm);
    }
    @Test void providerFailureLeavesNoPartialMessagesOrProgressChangesAndSafeHttpError() throws Exception {
        var f = fixture(true); UUID id = sessions.create(f.game(), 3, null).session().id();
        doThrow(new LlmException(LlmException.Code.LLM_TIMEOUT, "LLM request timed out")).when(llm).generate(any());
        var error = json(request("POST", path(id) + "/messages", Map.of("question", "How do I move?")), 504);
        assertThat(error.path("code").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(error.has("trace")).isFalse(); assertThat(error.path("timestamp").asText()).isNotBlank();
        assertThat(sessions.history(id, null, 100)).isEmpty();
        assertThat(sessions.current(id).session().revision()).isZero();
    }
    @Test void finalContinueStartsPlayingSameSessionAndCompletionBlocksFurtherWrites() throws Exception {
        var f = fixture(true); UUID id = sessions.create(f.game(), 3, TeachingDepth.QUICK).session().id();
        sessions.navigate(id, GameSession.Action.CONTINUE, 0);
        var playing = sessions.navigate(id, GameSession.Action.CONTINUE, 1);
        assertThat(playing.readyToPlay()).isTrue(); assertThat(playing.session().id()).isEqualTo(id);
        assertThat(playing.notice()).contains("Ready to play");
        var result = sessions.ask(id, "How far do I move?");
        var completed = json(request("POST", path(id) + "/complete", Map.of("expectedRevision", result.revision())), 200);
        assertThat(completed.path("session").path("stage").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("session").path("endedAt").asText()).isNotBlank();
        json(request("POST", path(id) + "/messages", Map.of("question", "How far?")), 409);
        json(request("POST", path(id) + "/teaching/actions", Map.of("action", "CONTINUE", "expectedRevision", 4)), 409);
        assertThat(sessions.history(id, null, 100)).hasSize(2);
    }
    @Test void boundedRecentContextAndCursorHistoryExcludeOtherSessions() {
        var f = fixture(true); UUID id = sessions.create(f.game(), 3, null).session().id();
        UUID other = sessions.create(f.game(), 3, null).session().id();
        sessions.ask(other, "A different session secret");
        for (int i = 0; i < 5; i++) { sessions.ask(id, "Question " + i); }
        var context = contexts.load(id).conversation();
        assertThat(context.recentMessages()).hasSize(4);
        assertThat(context.recentMessages().toString()).contains("Question 3", "Question 4").doesNotContain("Question 0", "secret");
        assertThat(context.recentMessages().stream().mapToInt(t -> t.content().length()).sum()).isLessThanOrEqualTo(1000);
        var latest = sessions.history(id, null, 2);
        assertThat(latest).extracting(ConversationMessage::sequenceNumber).containsExactly(9L, 10L);
        assertThat(sessions.history(id, 9L, 2)).extracting(ConversationMessage::sequenceNumber).containsExactly(7L, 8L);
    }
    @Test void questionDuringConcurrentNavigationIsRejectedWithoutSavingStaleExchange() throws Exception {
        var f = fixture(true); UUID id = sessions.create(f.game(), 3, null).session().id();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return new LlmResponse("", false, List.of());
        }).when(llm).generate(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> sessions.ask(id, "How do I move?"));
            try {
                assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
                sessions.navigate(id, GameSession.Action.CONTINUE, 0);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> pending.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(SessionException.class);
        }
        assertThat(sessions.history(id, null, 100)).isEmpty();
        assertThat(sessions.current(id).lesson().key()).isEqualTo("END");
    }
    @Test void concurrentIdenticalNavigationOnlyAdvancesOnce() throws Exception {
        var f = fixture(false); UUID id = sessions.create(f.game(), 3, null).session().id();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    try { sessions.navigate(id, GameSession.Action.CONTINUE, 0); return true; }
                    catch (SessionException e) { assertThat(e.code()).isEqualTo(SessionException.Code.SESSION_CONFLICT); return false; }
                }));
            }
            start.countDown(); int success = 0;
            for (var task : tasks) { if (task.get(20, TimeUnit.SECONDS)) { success++; } }
            assertThat(success).isEqualTo(1);
        }
        assertThat(sessions.current(id).session().revision()).isEqualTo(1);
        assertThat(sessions.current(id).session().stage()).isEqualTo(SessionStage.TEACHING);
    }
    @Test void concurrentQuestionsOnlyPersistOneAtomicExchange() throws Exception {
        var f = fixture(true); UUID id = sessions.create(f.game(), 3, null).session().id();
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown(); assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return new LlmResponse("", false, List.of());
        }).when(llm).generate(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(executor.submit(() -> {
                    try { sessions.ask(id, "How do I move?"); return true; }
                    catch (SessionException e) { assertThat(e.code()).isEqualTo(SessionException.Code.SESSION_CONFLICT); return false; }
                }));
            }
            try { assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
            int successes = 0;
            for (var task : tasks) { if (task.get(20, TimeUnit.SECONDS)) { successes++; } }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(sessions.history(id, null, 100)).extracting(ConversationMessage::role)
                .containsExactly(ConversationMessage.Role.USER, ConversationMessage.Role.ASSISTANT);
        assertThat(sessions.current(id).session().revision()).isEqualTo(1);
    }
    @Test void failureWhileStoringAssistantRollsBackUserMessageAndRevision() {
        var f = fixture(true); UUID id = sessions.create(f.game(), 3, null).session().id();
        jdbc.execute("CREATE FUNCTION session_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.session_id='" + id + "'::uuid AND NEW.role='ASSISTANT' THEN RAISE EXCEPTION 'fixture write failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER session_test_failure BEFORE INSERT ON conversation_messages FOR EACH ROW EXECUTE FUNCTION session_test_failure()");
        try {
            assertThatThrownBy(() -> sessions.ask(id, "How do I move?")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(sessions.history(id, null, 100)).isEmpty();
            assertThat(sessions.current(id).session().revision()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER session_test_failure ON conversation_messages");
            jdbc.execute("DROP FUNCTION session_test_failure()");
        }
    }
    @Test void validatesHttpInputMissingResourcesAndUnconfiguredFlowBeforeProviders() throws Exception {
        var f = fixture(false);
        json(request("POST", "/api/v1/sessions", Map.of("gameId", f.game(), "playerCount", 5)), 400);
        json(request("POST", "/api/v1/sessions", Map.of("gameId", f.game(), "playerCount", 3, "teachingDepth", "LEARN")), 400);
        json(request("POST", "/api/v1/sessions", Map.of("gameId", f.game())), 400);
        json(request("POST", "/api/v1/sessions", Map.of("gameId", UUID.randomUUID(), "playerCount", 3)), 404);
        json(request("GET", path(UUID.randomUUID()), null), 404);
        var unconfigured = games.createVersion(f.game(), "Unconfigured", "base", "en", null, true, true);
        assertThat(json(request("POST", "/api/v1/sessions", Map.of("gameId", f.game(), "playerCount", 3)), 409).path("code").asText()).isEqualTo("TEACHING_FLOW_NOT_FOUND");
        UUID id = sessions.create(CATAN, 3, null).session().id();
        json(request("POST", path(id) + "/messages", Map.of("question", " ")), 400);
        json(request("POST", path(id) + "/messages", Map.of("question", "x".repeat(4001))), 400);
        json(request("POST", path(id) + "/teaching/actions", Map.of("action", "CONTINUE")), 400);
        json(request("GET", path(id) + "/messages?limit=101", null), 400);
        json(request("GET", path(id) + "/messages?beforeSequence=0", null), 400);
        assertThat(unconfigured.getId()).isNotNull();
        verifyNoInteractions(llm); verify(embeddings, never()).embed(anyString());
    }
    @Test void genericTeachingPublicationHttpValidatesAndExposesOpenApi() throws Exception {
        var f = fixture(false);
        String url = "/api/v1/game-versions/" + f.version() + "/teaching-flows";
        var published = json(request("POST", url, Map.of("name", "Published through HTTP", "sections", List.of(section("ONLY", 0, null)))), 201);
        assertThat(published.path("sections").get(0).path("key").asText()).isEqualTo("ONLY");
        assertThat(json(request("GET", url, null), 200).path("id")).isEqualTo(published.path("id"));
        json(request("POST", url, Map.of("name", "Empty", "sections", List.of())), 400);
        json(request("POST", url, Map.of("name", "Duplicate", "sections", List.of(section("ONE", 0, null), section("ONE", 0, null)))), 400);
        var paths = json(request("GET", "/v3/api-docs", null), 200).path("paths");
        assertThat(paths.has("/api/v1/sessions")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}/messages")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}/teaching/actions")).isTrue();
        assertThat(paths.has("/api/v1/game-versions/{versionId}/teaching-flows")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}/setup")).isTrue();
    }
    @Test void databaseEnforcesFlowVersionAndLessonOwnershipAndLifecycle() {
        var f = fixture(false); var other = fixture(false); UUID id = sessions.create(f.game(), 3, null).session().id();
        assertThatThrownBy(() -> jdbc.update("UPDATE game_sessions SET game_version_id=? WHERE id=?", other.version(), id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE game_sessions SET current_section_key='FOREIGN' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE game_sessions SET stage='COMPLETED' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(sessions.current(id).session().revision()).isZero();
    }
    @Test void v5UpgradePreservesExistingRulesAndAddsRepeatableReferenceTeaching() {
        String schema = "session_upgrade";
        jdbc.execute("CREATE SCHEMA " + schema);
        var initial = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).schemas(schema)
                .locations("classpath:db/migration").target("5").load();
        initial.migrate();
        UUID marker = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".games(id,slug,name,min_players,max_players,estimated_duration_minutes,active) VALUES (?,'preserved','Preserved',1,4,10,true)", marker);
        UUID document = UUID.randomUUID(); UUID chunk = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".rule_documents(id,game_version_id,name,source_type,version,language,source_reference,file_name,imported_at,normalized_text,chunk_size,chunk_overlap,chunk_count) "
                + "VALUES (?,?,'Preserved rules','RULEBOOK','1','en','Original fixture','rules.txt',CURRENT_TIMESTAMP,'Move one space.',1200,200,1)", document, CATAN_VERSION);
        jdbc.update("INSERT INTO " + schema + ".rule_chunks(id,document_id,game_version_id,content,chunk_index,start_offset,end_offset) VALUES (?,?,?,'Move one space.',0,0,15)", chunk, document, CATAN_VERSION);
        jdbc.update("INSERT INTO " + schema + ".rule_chunk_embeddings(chunk_id,game_version_id,provider,model,dimensions,embedding) VALUES (?,?,'fixture','preserved',3,'[1,0,0]'::public.vector)", chunk, CATAN_VERSION);
        var upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).schemas(schema)
                .locations("classpath:db/migration").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT name FROM " + schema + ".games WHERE id=?", String.class, marker)).isEqualTo("Preserved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".teaching_sections", Integer.class)).isEqualTo(21);
        assertThat(jdbc.queryForObject("SELECT normalized_text FROM " + schema + ".rule_documents WHERE id=?", String.class, document)).isEqualTo("Move one space.");
        assertThat(jdbc.queryForObject("SELECT content FROM " + schema + ".rule_chunks WHERE id=?", String.class, chunk)).isEqualTo("Move one space.");
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM " + schema + ".rule_chunk_embeddings WHERE chunk_id=?", String.class, chunk)).isEqualTo("[1,0,0]");
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    private SetupStep step(String key, int order) {
        return new SetupStep(key, order, key + " setup", "Put the original pieces on the table.", List.of("Keep the box nearby."), List.of("Confirm this yourself."));
    }
    private TeachingSection trigger(String key, int order) {
        return new TeachingSection(key, order, "Prepare the table", "Continue to begin setup.", null, null, null, null, true);
    }
    private Fixture guided(boolean indexed) {
        var f = fixture(indexed);
        setups.publish(f.version(), "Original game setup", List.of(step("FIRST", 0), step("LAST", 1)));
        return new Fixture(f.game(), f.version(), teaching.publish(f.version(), "Guided lessons",
                List.of(section("START", 0, null), trigger("PREPARE", 1), section("END", 2, null))));
    }
    @Test void genericGuidedJourneyThroughHttpReturnsToSavedLessonThenSameSessionPlaying() throws Exception {
        var f = guided(false); UUID id = sessions.create(f.game(), 3, null).session().id();
        assertThat(setups.current(id).stepNumber()).isZero();
        sessions.navigate(id, GameSession.Action.CONTINUE, 0);
        var entered = json(request("POST", path(id) + "/teaching/actions", Map.of("action", "CONTINUE", "expectedRevision", 1)), 200);
        assertThat(entered.path("session").path("stage").asText()).isEqualTo("SETUP");
        assertThat(entered.path("session").path("completedSections").size()).isEqualTo(1);
        var first = json(request("GET", path(id) + "/setup", null), 200);
        assertThat(first.path("step").path("key").asText()).isEqualTo("FIRST");
        assertThat(first.path("step").path("warnings").get(0).asText()).isEqualTo("Confirm this yourself.");
        json(request("POST", path(id) + "/setup/complete", Map.of("expectedRevision", 2)), 409);
        json(request("POST", path(id) + "/teaching/actions", Map.of("action", "CONTINUE", "expectedRevision", 2)), 409);
        json(request("POST", path(id) + "/setup/previous", Map.of("expectedRevision", 2)), 200);
        json(request("POST", path(id) + "/setup/repeat", Map.of("expectedRevision", 3)), 200);
        var last = json(request("POST", path(id) + "/setup/next", Map.of("expectedRevision", 4)), 200);
        assertThat(last.path("stepNumber").asInt()).isEqualTo(2);
        json(request("POST", path(id) + "/setup/next", Map.of("expectedRevision", 4)), 409);
        json(request("POST", path(id) + "/setup/next", Map.of("expectedRevision", 5)), 409);
        var resumed = json(request("POST", path(id) + "/setup/complete", Map.of("expectedRevision", 5)), 200);
        assertThat(resumed.path("session").path("stage").asText()).isEqualTo("TEACHING");
        assertThat(resumed.path("session").path("currentSectionKey").asText()).isEqualTo("PREPARE");
        assertThat(resumed.path("session").path("setup").path("completed").asBoolean()).isTrue();
        sessions.navigate(id, GameSession.Action.CONTINUE, 6);
        sessions.navigate(id, GameSession.Action.PREVIOUS, 7);
        assertThat(sessions.navigate(id, GameSession.Action.CONTINUE, 8).lesson().key()).isEqualTo("END");
        assertThat(sessions.navigate(id, GameSession.Action.CONTINUE, 9).readyToPlay()).isTrue();
        json(request("POST", path(id) + "/setup/restart", Map.of("expectedRevision", 10)), 409);
        verifyNoInteractions(llm); verify(embeddings, never()).embed(anyString());
    }
    @Test void questionsDuringSetupKeepProgressUseStepContextAndPersistCitations() {
        var f = guided(true); UUID id = sessions.create(f.game(), 3, null).session().id();
        setups.act(id, SetupService.Action.START, 0);
        setups.act(id, SetupService.Action.NEXT, 1);
        var before = setups.current(id).session();
        var result = sessions.ask(id, "How do I move this?");
        assertThat(result.answer().sources()).hasSize(1);
        assertThat(setups.current(id).session().setup()).isEqualTo(before.setup());
        assertThat(sessions.current(id).session().stage()).isEqualTo(SessionStage.SETUP);
        verify(llm).generate(argThat(q -> q.input().contains("LAST setup") && q.input().contains("SETUP") && q.instructions().contains("setup instructions are NOT evidence")));
        verify(embeddings).embed(argThat(q -> q.contains("Current setup step: LAST setup")));
        assertThat(sessions.history(id, null, 100)).hasSize(2);
        doThrow(new LlmException(LlmException.Code.LLM_TIMEOUT, "LLM request timed out")).when(llm).generate(any());
        assertThatThrownBy(() -> sessions.ask(id, "What about now?")).isInstanceOf(LlmException.class);
        assertThat(setups.current(id).session().revision()).isEqualTo(3);
        assertThat(setups.current(id).session().setup()).isEqualTo(before.setup());
    }
    @Test void unsupportedQuestionDuringSetupDoesNotAdvanceAndRestartPreservesHistory() {
        var f = guided(false); UUID id = sessions.create(f.game(), 3, null).session().id();
        setups.act(id, SetupService.Action.START, 0);
        setups.act(id, SetupService.Action.NEXT, 1);
        assertThat(sessions.ask(id, "Can I fly?").answer().confidence()).isEqualTo(RuleAnswer.Confidence.LOW);
        var restarted = setups.act(id, SetupService.Action.RESTART, 3);
        assertThat(restarted.session().setup().completedStepKeys()).isEmpty();
        assertThat(restarted.step().key()).isEqualTo("FIRST");
        assertThat(restarted.session().setup().returnSectionKey()).isEqualTo("START");
        assertThat(sessions.history(id, null, 100)).hasSize(2);
        setups.act(id, SetupService.Action.NEXT, 4);
        setups.act(id, SetupService.Action.PREVIOUS, 5);
        assertThat(setups.current(id).session().setup().completedStepKeys()).containsExactly("FIRST");
        setups.act(id, SetupService.Action.NEXT, 6);
        setups.act(id, SetupService.Action.COMPLETE, 7);
        sessions.navigate(id, GameSession.Action.CONTINUE, 8);
        var restartedAfter = setups.act(id, SetupService.Action.RESTART, 9);
        assertThat(restartedAfter.session().setup().returnSectionKey()).isEqualTo("PREPARE");
        assertThat(restartedAfter.session().completedSections()).containsExactly("START");
        setups.act(id, SetupService.Action.NEXT, 10);
        assertThat(setups.act(id, SetupService.Action.COMPLETE, 11).session().currentSectionKey()).isEqualTo("PREPARE");
    }
    @Test void newSetupPublicationsAndCatalogDefaultsDoNotRepinSessions() {
        var f = guided(false); var original = sessions.create(f.game(), 3, null).session();
        var replacement = setups.publish(f.version(), "Replacement setup", List.of(step("NEW", 0)));
        assertThat(sessions.create(f.game(), 3, null).session().setup().flowId()).isEqualTo(replacement.id());
        games.createVersion(f.game(), "Other", "base", "en", null, true, true);
        assertThat(setups.act(original.id(), SetupService.Action.START, 0).flowId()).isEqualTo(original.setup().flowId());
        assertThat(setups.current(original.id()).step().key()).isEqualTo("FIRST");
    }
    @Test void setupPublicationAndActionsValidateHttpAndOwnership() throws Exception {
        var f = fixture(false); UUID id = sessions.create(f.game(), 3, null).session().id();
        String url = "/api/v1/game-versions/" + f.version() + "/setup-flows";
        assertThat(json(request("GET", path(id) + "/setup", null), 409).path("code").asText()).isEqualTo("SETUP_FLOW_NOT_FOUND");
        json(request("POST", url, Map.of("name", "Empty", "steps", List.of())), 400);
        json(request("POST", url, Map.of("name", "Duplicates", "steps", List.of(step("ONE", 0), step("ONE", 1)))), 400);
        json(request("POST", url, Map.of("name", "Missing", "steps", List.of(Map.of("key", "ONE", "title", "Missing instruction")))), 400);
        var published = json(request("POST", url, Map.of("name", "Original", "steps", List.of(step("ONE", 0)))), 201);
        assertThat(json(request("GET", url, null), 200).path("id")).isEqualTo(published.path("id"));
        json(request("GET", "/api/v1/game-versions/" + UUID.randomUUID() + "/setup-flows", null), 404);
        json(request("GET", path(UUID.randomUUID()) + "/setup", null), 404);
        json(request("POST", path(id) + "/setup/start", Map.of()), 400);
        json(request("POST", path(id) + "/setup/start", Map.of("expectedRevision", -1)), 400);
        json(request("POST", path(id) + "/setup/restart", Map.of("expectedRevision", 0)), 409);
        json(request("POST", path(id) + "/setup/start", Map.of("expectedRevision", 0)), 200);
        json(request("POST", path(id) + "/setup/start", Map.of("expectedRevision", 1)), 409);
        setups.act(id, SetupService.Action.COMPLETE, 1);
        sessions.complete(id, 2);
        json(request("POST", path(id) + "/setup/repeat", Map.of("expectedRevision", 3)), 409);
        assertThat(json(request("GET", path(id) + "/setup", null), 200).path("session").path("setup").path("completed").asBoolean()).isTrue();
        var other = guided(false);
        assertThatThrownBy(() -> jdbc.update("UPDATE game_sessions SET setup_flow_id=? WHERE id=?", sessions.create(other.game(), 3, null).session().setup().flowId(), id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE game_sessions SET current_setup_step_key='FOREIGN' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE game_sessions SET setup_return_section_key='FOREIGN' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void triggerAtFinalLessonReturnsToTeachingBeforePlayingForEveryDepth() {
        var f = fixture(false);
        setups.publish(f.version(), "Single step", List.of(step("ONLY", 0)));
        teaching.publish(f.version(), "Single trigger", List.of(trigger("PREPARE", 0)));
        for (var depth : TeachingDepth.values()) {
            UUID id = sessions.create(f.game(), 3, depth).session().id();
            assertThat(sessions.navigate(id, GameSession.Action.CONTINUE, 0).session().stage()).isEqualTo(SessionStage.SETUP);
            assertThat(setups.act(id, SetupService.Action.COMPLETE, 1).session().stage()).isEqualTo(SessionStage.TEACHING);
            assertThat(sessions.current(id).readyToPlay()).isFalse();
            assertThat(sessions.navigate(id, GameSession.Action.CONTINUE, 2).readyToPlay()).isTrue();
        }
    }
    @Test void setupTriggerRequiresConfiguredFlowAndCannotBeDepthSpecific() throws Exception {
        var f = fixture(false); String url = "/api/v1/game-versions/" + f.version() + "/teaching-flows";
        assertThat(json(request("POST", url, Map.of("name", "Trigger", "sections", List.of(trigger("SETUP", 0)))), 409).path("code").asText()).isEqualTo("SETUP_FLOW_NOT_FOUND");
        assertThat(teaching.current(f.version()).id()).isEqualTo(f.flow().id());
        setups.publish(f.version(), "Setup", List.of(step("ONE", 0)));
        json(request("POST", url, Map.of("name", "Two", "sections", List.of(trigger("ONE", 0), trigger("TWO", 1)))), 400);
        json(request("POST", url, Map.of("name", "Skip", "sections", List.of(Map.of("key", "ONE", "order", 0, "title", "One", "explanation", "One", "depths", List.of("DETAILED"), "setupTrigger", true)))), 400);
    }
    @Test void duplicateSetupActionsAllowOnlyOneRevisionWinner() throws Exception {
        var f = guided(false); UUID id = sessions.create(f.game(), 3, null).session().id(); setups.act(id, SetupService.Action.START, 0);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); var tasks = new ArrayList<Future<Boolean>>();
            for (int i=0;i<2;i++) { tasks.add(executor.submit(() -> { start.await();
                try { setups.act(id, SetupService.Action.NEXT, 1); return true; }
                catch (SessionException e) { assertThat(e.code()).isEqualTo(SessionException.Code.SESSION_CONFLICT); return false; }
            })); }
            start.countDown(); int successes=0;
            for (var task : tasks) { if (task.get(20, TimeUnit.SECONDS)) { successes++; } }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(setups.current(id).session().revision()).isEqualTo(2);
        assertThat(setups.current(id).step().key()).isEqualTo("LAST");
    }
    @Test void setupRestartMakesInflightQuestionStaleWithoutPartialHistory() throws Exception {
        var f = guided(true); UUID id = sessions.create(f.game(), 3, null).session().id(); setups.act(id, SetupService.Action.START, 0);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(20, TimeUnit.SECONDS)).isTrue(); return new LlmResponse("", false, List.of()); }).when(llm).generate(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var question = executor.submit(() -> sessions.ask(id, "How do I move?"));
            try { assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue(); setups.act(id, SetupService.Action.RESTART, 1); }
            finally { release.countDown(); }
            assertThatThrownBy(() -> question.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(SessionException.class);
        }
        assertThat(sessions.history(id, null, 100)).isEmpty();
        assertThat(setups.current(id).session().revision()).isEqualTo(2);
    }
    @Test void v6UpgradePreservesSessionsHistoryAndUserPublishedTeaching() {
        String schema = "setup_upgrade"; jdbc.execute("CREATE SCHEMA " + schema);
        var initial = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).schemas(schema)
                .locations("classpath:db/migration").target("6").load(); initial.migrate();
        UUID id = UUID.randomUUID(); UUID custom = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".game_sessions(id,game_version_id,teaching_flow_id,player_count,teaching_depth,stage,status,current_section_key,completed_sections,revision,created_at) "
                + "VALUES (?,?,'00000000-0000-0000-0000-000000000006',3,'STANDARD','TEACHING','ACTIVE','INTRODUCTION','[]',5,CURRENT_TIMESTAMP)", id, CATAN_VERSION);
        jdbc.update("INSERT INTO " + schema + ".conversation_messages(id,session_id,sequence_number,role,content,created_at) VALUES (?,?,1,'USER','Preserve me',CURRENT_TIMESTAMP)", UUID.randomUUID(), id);
        jdbc.update("INSERT INTO " + schema + ".teaching_flows(id,game_version_id,name,created_at) VALUES (?,?,'User publication',CURRENT_TIMESTAMP)", custom, CATAN_VERSION);
        var upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).schemas(schema).locations("classpath:db/migration").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM " + schema + ".game_sessions WHERE id=?", id);
        assertThat(row.get("revision")).isEqualTo(5L); assertThat(row.get("setup_flow_id")).isNull();
        assertThat(row.get("current_section_key")).isEqualTo("INTRODUCTION");
        assertThat(jdbc.queryForObject("SELECT content FROM " + schema + ".conversation_messages WHERE session_id=?", String.class, id)).isEqualTo("Preserve me");
        assertThat(jdbc.queryForObject("SELECT id FROM " + schema + ".teaching_flows ORDER BY created_at DESC,id DESC LIMIT 1", UUID.class)).isEqualTo(custom);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".setup_steps", Integer.class)).isEqualTo(6);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    @Test void catanReferenceGuidesAllStepsAndResumesTeachingWithoutPaidCalls() {
        UUID id = sessions.create(CATAN, 3, TeachingDepth.QUICK).session().id();
        var lesson = sessions.current(id);
        while (!lesson.lesson().setupTrigger()) { lesson = sessions.navigate(id, GameSession.Action.CONTINUE, lesson.session().revision()); }
        var priorCompletion = lesson.session().completedSections();
        lesson = sessions.navigate(id, GameSession.Action.CONTINUE, lesson.session().revision());
        assertThat(lesson.session().stage()).isEqualTo(SessionStage.SETUP);
        var setup = setups.current(id); assertThat(setup.totalSteps()).isEqualTo(6);
        while (setup.stepNumber() < setup.totalSteps()) { setup = setups.act(id, SetupService.Action.NEXT, setup.session().revision()); }
        var resumed = setups.act(id, SetupService.Action.COMPLETE, setup.session().revision());
        assertThat(resumed.session().currentSectionKey()).isEqualTo("PHYSICAL_SETUP");
        assertThat(resumed.session().completedSections()).isEqualTo(priorCompletion);
        lesson = sessions.current(id);
        while (!lesson.readyToPlay()) { lesson = sessions.navigate(id, GameSession.Action.CONTINUE, lesson.session().revision()); }
        assertThat(lesson.session().id()).isEqualTo(id);
        assertThat(lesson.session().setup().completed()).isTrue();
        verifyNoInteractions(llm); verify(embeddings, never()).embed(anyString());
    }
    @Test void setupPublicationWriteFailureRollsBackWholeFlow() {
        var f = fixture(false);
        jdbc.execute("CREATE FUNCTION setup_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.step_key='FAIL' THEN RAISE EXCEPTION 'fixture write failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER setup_test_failure BEFORE INSERT ON setup_steps FOR EACH ROW EXECUTE FUNCTION setup_test_failure()");
        try {
            assertThatThrownBy(() -> setups.publish(f.version(), "Failing flow", List.of(step("FIRST", 0), step("FAIL", 1))))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM setup_flows WHERE game_version_id=?", Integer.class, f.version())).isZero();
        } finally { jdbc.execute("DROP TRIGGER setup_test_failure ON setup_steps"); jdbc.execute("DROP FUNCTION setup_test_failure()"); }
    }
}
