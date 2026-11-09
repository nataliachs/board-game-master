package com.boardgamemaster.rag;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("integration") @Testcontainers @ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "boardgame.embedding.dimensions=4")
class RetrievalEvaluationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @MockitoBean EmbeddingClient embeddings;
    @MockitoBean LlmClient llm;
    @Autowired GameService games;
    @Autowired RuleDocumentService documents;
    @Autowired RuleEmbeddingService indexing;
    @Autowired RuleRetrievalService retrieval;
    @Autowired RuleAnswerService answers;
    @Autowired RuleVectorStore store;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final EmbeddingProfile profile = new EmbeddingProfile("fixture", "evaluation", 4);
    private final Map<String, Embedding> vectors = new HashMap<>();

    @BeforeEach void provider() {
        when(embeddings.profile()).thenReturn(profile);
        when(embeddings.embed(anyString())).thenAnswer(c -> vectors.getOrDefault(c.getArgument(0), vector(1,0,0,0)));
    }
    private Embedding vector(float... values) { var list = new ArrayList<Float>(); for (float v : values) { list.add(v); } return new Embedding(list); }
    private Embedding vector(JsonNode values) { var list = new ArrayList<Float>(); values.forEach(v -> list.add(v.floatValue())); return new Embedding(list); }
    private UUID game() { return games.createGame("eval-" + UUID.randomUUID(), "Original evaluation", null, 2, 4, 15, null, true).getId(); }
    private UUID version(UUID game, String language, boolean defaults) {
        return games.createVersion(game, "Edition " + UUID.randomUUID(), "base", language, null, true, defaults).getId();
    }
    private RuleDocument index(UUID version, RuleSourceType type, String language, String text, Embedding vector) {
        var d = documents.ingest(version, new DocumentMetadata("Original evaluation", type, "1", language, "Self-authored test data"),
                "original.md", text.getBytes(StandardCharsets.UTF_8));
        vectors.put(text, vector);
        store.pending(version, d.getId(), profile).forEach(c -> vectors.put(c.content(), vector));
        indexing.index(version, d.getId());
        return d;
    }
    private HttpResponse<String> post(String path, Object body) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void evaluatesCheckedInDatasetSeparatelyFromGenerationAndWritesDiagnostics() throws Exception {
        JsonNode dataset;
        try (var input = getClass().getResourceAsStream("/evaluation/catan-rule-questions.json")) { dataset = mapper.readTree(input); }
        UUID game = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID version = version(game, "en", false);
        UUID otherEdition = version(game, "en", false);
        UUID otherGame = version(game(), "en", false);
        var topicsBySection = new HashMap<String, String>();
        for (var doc : dataset.path("documents")) {
            topicsBySection.put(doc.path("section").asText(), doc.path("key").asText());
            String text = "# " + doc.path("section").asText() + "\n\n" + doc.path("text").asText();
            var v = vector(doc.path("vector"));
            index(version, RuleSourceType.RULEBOOK, "en", text, v);
            index(version, RuleSourceType.RULEBOOK, "en", text, v); // duplicate imports must not occupy top-K
            index(otherEdition, RuleSourceType.RULEBOOK, "en", text, v);
            index(otherGame, RuleSourceType.RULEBOOK, "en", text, v);
        }
        int positives = 0, negatives = 0, hits = 0, correctAbstentions = 0; double reciprocalRanks = 0;
        var results = new ArrayList<Map<String, Object>>();
        for (var question : dataset.path("questions")) {
            String text = question.path("question").asText(); vectors.put(text, vector(question.path("vector")));
            var found = retrieval.search(version, text, 3);
            assertThat(found.matches()).allSatisfy(m -> assertThat(m.gameVersionId()).isEqualTo(version));
            if (question.path("mustRetrieveSection").isNull()) {
                negatives++; assertThat(found.matches()).isEmpty(); correctAbstentions++;
            } else {
                positives++;
                var sections = found.matches().stream().map(RuleVectorStore.Match::sectionTitle).toList();
                var expectedTopics = new ArrayList<String>(); question.path("expectedTopics").forEach(t -> expectedTopics.add(t.asText()));
                assertThat(sections.stream().map(topicsBySection::get).toList()).containsAll(expectedTopics);
                int rank = sections.indexOf(question.path("mustRetrieveSection").asText());
                assertThat(rank).isGreaterThanOrEqualTo(0); hits++; reciprocalRanks += 1d / (rank + 1);
            }
            results.add(Map.of("question", text, "expectedTopics", mapper.convertValue(question.path("expectedTopics"), List.class),
                    "retrievedSections", found.matches().stream().map(RuleVectorStore.Match::sectionTitle).toList(), "diagnostics", found.diagnostics()));
        }
        double recall = (double) hits / positives, mrr = reciprocalRanks / positives;
        assertThat(recall).isEqualTo(1); assertThat(mrr).isEqualTo(1); assertThat(correctAbstentions).isEqualTo(negatives);
        var report = Map.of("fixture", "catan-rule-questions.json", "embeddingMode", "hand-assigned vectors; not live relevance",
                "positiveQuestions", positives, "negativeQuestions", negatives, "recallAt3", recall, "mrrAt3", mrr,
                "correctAbstentions", correctAbstentions, "results", results);
        Path output = Path.of("build", "reports", "retrieval-evaluation.json"); Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        verifyNoInteractions(llm);
    }

    @Test void metadataFiltersApplyBeforeCandidateSelectionAndExpansionIsExplicit() {
        UUID version = version(game(), "en", false);
        var base = index(version, RuleSourceType.RULEBOOK, "en", "Base rule.", vector(1,0,0,0));
        var faq = index(version, RuleSourceType.FAQ, "en", "FAQ rule.", vector(.6f,.8f,0,0));
        var expansion = index(version, RuleSourceType.EXPANSION, "en", "Expansion rule.", vector(1,0,0,0));
        var selected = new RetrievalFilters("EN", Set.of(RuleSourceType.FAQ), Set.of(faq.getId()), null);
        assertThat(retrieval.search(version, "query", 1, .5, selected).matches()).extracting(RuleVectorStore.Match::documentId).containsExactly(faq.getId());
        assertThat(retrieval.search(version, "query", 5).matches()).extracting(RuleVectorStore.Match::documentId)
                .contains(base.getId(), faq.getId()).doesNotContain(expansion.getId());
        var withExpansion = new RetrievalFilters(null, null, null, Set.of(expansion.getId()));
        assertThat(retrieval.search(version, "query", 5, null, withExpansion).matches())
                .extracting(RuleVectorStore.Match::documentId).contains(expansion.getId());
    }

    @Test void languageFiltersCannotReachAnotherRulesetAndEmptyFilterSkipsQueryCall() {
        UUID game = game(); UUID english = version(game, "en", false); UUID indonesian = version(game, "id", false);
        index(english, RuleSourceType.RULEBOOK, "en", "English rule.", vector(1,0,0,0));
        index(indonesian, RuleSourceType.RULEBOOK, "id", "Original Indonesian fixture.", vector(1,0,0,0));
        clearInvocations(embeddings);
        var mismatch = new RetrievalFilters("id", null, null, null);
        assertThat(retrieval.search(english, "query", null, null, mismatch).matches()).isEmpty();
        verify(embeddings, never()).embed(anyString());
        assertThat(retrieval.search(indonesian, "query", null).diagnostics().filters().language()).isEqualTo("id");
    }

    @Test void foreignDocumentsAndNonExpansionSelectionsFailBeforePaidCalls() {
        UUID chosen = version(game(), "en", false); UUID other = version(game(), "en", false);
        var foreign = index(other, RuleSourceType.EXPANSION, "en", "Foreign rule.", vector(1,0,0,0));
        var base = index(chosen, RuleSourceType.RULEBOOK, "en", "Base rule.", vector(1,0,0,0));
        clearInvocations(embeddings);
        assertThatThrownBy(() -> retrieval.search(chosen, "query", null, null,
                new RetrievalFilters(null, null, null, Set.of(foreign.getId())))).isInstanceOf(RuleImportException.class);
        assertThatThrownBy(() -> retrieval.search(chosen, "query", null, null,
                new RetrievalFilters(null, null, null, Set.of(base.getId())))).isInstanceOf(RetrievalException.class);
        verify(embeddings, never()).embed(anyString());
    }

    @Test void duplicateHeavyCandidatesDoNotStarveTopKWithinPoolAndDiagnosticsCountSuppression() {
        UUID version = version(game(), "en", false);
        for (int i = 0; i < 8; i++) { index(version, RuleSourceType.RULEBOOK, "en", "Repeated rule.", vector(1,0,0,0)); }
        index(version, RuleSourceType.RULEBOOK, "en", "Second distinct rule.", vector(.8f,.6f,0,0));
        var result = retrieval.search(version, "query", 2);
        assertThat(result.matches()).hasSize(2);
        assertThat(result.diagnostics().candidates()).isEqualTo(9);
        assertThat(result.diagnostics().duplicatesRemoved()).isEqualTo(7);
    }

    @Test void thresholdWeakEvidenceStopsGenerationAndAnswerUsesFilters() throws Exception {
        UUID game = game(); UUID version = version(game, "en", true);
        var base = index(version, RuleSourceType.RULEBOOK, "en", "Move one space.", vector(.6f,.8f,0,0));
        index(version, RuleSourceType.FAQ, "en", "Another rule.", vector(1,0,0,0));
        var filters = Map.of("sourceTypes", List.of("RULEBOOK"), "documentIds", List.of(base.getId()));
        var response = post("/api/v1/games/" + game + "/questions", Map.of("question", "query", "topK", 1,
                "similarityThreshold", .7, "filters", filters));
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).contains("LOW", RuleAnswerService.FALLBACK);
        verifyNoInteractions(llm);
        UUID chunk = store.pending(version, base.getId(), new EmbeddingProfile("fixture", "other", 4)).getFirst().id();
        when(llm.generate(any())).thenReturn(new LlmResponse("Move one space.", true, List.of(new LlmResponse.Citation(chunk, "Move one space."))));
        response = post("/api/v1/games/" + game + "/questions", Map.of("question", "query", "topK", 1,
                "similarityThreshold", .5, "filters", filters));
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).contains("MEDIUM", base.getId().toString());
        verify(llm).generate(argThat(r -> !r.input().contains("Another rule.")));
    }

    @Test void httpFiltersAndLimitsReturnMeaningfulResultsOrErrors() throws Exception {
        UUID version = version(game(), "en", false);
        index(version, RuleSourceType.ERRATA, "en", "Original erratum.", vector(1,0,0,0));
        String path = "/api/v1/game-versions/" + version + "/rules/search";
        var response = post(path, Map.of("query", "query", "filters", Map.of("sourceTypes", List.of("ERRATA"))));
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).contains("diagnostics", "ERRATA");
        for (Object body : List.of(Map.of("query", "query", "similarityThreshold", 1.1),
                Map.of("query", "query", "filters", Map.of("language", "invalid tag")),
                Map.of("query", "query", "filters", Map.of("sourceTypes", List.of("UNKNOWN"))))) {
            assertThat(post(path, body).statusCode()).isEqualTo(400);
        }
        var foreign = UUID.randomUUID();
        assertThat(post(path, Map.of("query", "query", "filters", Map.of("documentIds", List.of(foreign)))).statusCode()).isEqualTo(404);
    }
}
