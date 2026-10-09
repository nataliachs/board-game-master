package com.boardgamemaster.rag;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.ai.infrastructure.EmbeddingProperties;
import com.boardgamemaster.game.application.GameService;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.application.RuleDocumentService;
import com.boardgamemaster.rules.domain.RuleSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline gate evaluation: fixture answers/vectors are not live semantic-quality measurements. */
class ThresholdEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String, Object>> ROWS = Collections.synchronizedList(new ArrayList<>());
    private static final String SETUP = "Deal four mission cards at setup. Keep at least two; return the others to the deck bottom.";
    private static final String SCORING = "At final scoring, incomplete mission cards subtract their printed points from your score.";
    // Observed Game End score from user log request 39f2a28f-bff0-4ffc-b163-2323b588273d.
    // Replayed against fictional text: this does NOT simulate its embedding or establish a safe threshold.
    private static final double OBSERVED_RISK_SCORE = .43110639678163876;
    record Policy(String name, double retrieval, double answer) { }
    record Scenario(String name, String question, String source, double score, boolean supported) { }
    static List<Policy> policies() {
        return List.of(new Policy("baseline", .5, .5), new Policy("retrieval-only", .4, .5),
                new Policy("answer-only", .5, .4), new Policy("both-experimental", .4, .4));
    }
    static Stream<org.junit.jupiter.params.provider.Arguments> cases() {
        var scenarios = List.of(
                new Scenario("minimum", "Can I keep two mission cards?", SETUP, .7, true),
                new Scenario("maximum", "Can I keep all four?", SETUP, .7, true),
                new Scenario("below-minimum", "What if I keep only one?", SETUP, .7, true),
                new Scenario("above-deal", "Can I start with five?", SETUP, .7, true),
                new Scenario("returns", "Where do the ones I return go?", SETUP, .7, true),
                new Scenario("risk", "What's the risk?", SCORING, OBSERVED_RISK_SCORE, true),
                // Deliberately high-scoring irrelevant source: abstention depends on provider semantics.
                new Scenario("unsupported", "What is the publisher's phone number?", SETUP, .72, false));
        return policies().stream().flatMap(p -> scenarios.stream().map(s ->
                org.junit.jupiter.params.provider.Arguments.of(p, s)));
    }
    @BeforeAll static void clearReport() { ROWS.clear(); }

    @ParameterizedTest(name = "{0}: {1}") @MethodSource("cases")
    void compareBothGatesThroughRealServices(Policy policy, Scenario scenario) throws Exception {
        var run = pipeline(policy, scenario);
        boolean reachesModel = scenario.score() >= Math.max(policy.retrieval(), policy.answer());
        when(run.llm().generate(any())).thenAnswer(call -> {
            var input = JSON.readTree(((LlmRequest) call.getArgument(0)).input());
            assertThat(input.path("question").asText()).isEqualTo(scenario.question());
            assertThat(input.path("excerpts").get(0).path("content").asText()).isEqualTo(scenario.source());
            return scenario.supported()
                    ? new LlmResponse("Fixture answer; semantic quality is not measured.", true,
                        List.of(new LlmResponse.Citation(run.source().chunkId(), scenario.source())))
                    : new LlmResponse("", false, List.of());
        });
        var result = run.answers().askPinned(run.game(), run.version(), scenario.question(), run.context());
        boolean accepted = reachesModel && scenario.supported();
        assertThat(result.confidence()).isEqualTo(accepted ? RuleAnswer.Confidence.MEDIUM : RuleAnswer.Confidence.LOW);
        if (accepted) {
            assertThat(result.sources()).singleElement().satisfies(s -> {
                assertThat(s.quote()).isEqualTo(scenario.source());
                assertThat(s.gameVersionId()).isEqualTo(run.version());
            });
        } else {
            assertThat(result.answer()).isEqualTo(RuleAnswerService.FALLBACK);
            assertThat(result.sources()).isEmpty();
        }
        verify(run.llm(), times(reachesModel ? 1 : 0)).generate(any());
        ROWS.add(Map.of("policy", policy.name(), "retrievalThreshold", policy.retrieval(),
                "answerThreshold", policy.answer(), "scenario", scenario.name(), "fixtureScore", scenario.score(),
                "modelCalled", reachesModel, "accepted", accepted));
    }

    @Test void exactQuotationDoesNotProveAnswerEntailment() {
        var scenario = new Scenario("irrelevant-exact-quote", "What is the publisher's phone number?", SETUP, .72, false);
        var run = pipeline(new Policy("baseline", .5, .5), scenario);
        when(run.llm().generate(any())).thenReturn(new LlmResponse("The number is 555-0100.", true,
                List.of(new LlmResponse.Citation(run.source().chunkId(), SETUP))));
        var result = run.answers().askPinned(run.game(), run.version(), scenario.question(), run.context());
        // Characterizes the current semantic-validation gap, not desired product behavior.
        assertThat(result.confidence()).isEqualTo(RuleAnswer.Confidence.MEDIUM);
        assertThat(result.answer()).isEqualTo("The number is 555-0100.");
    }

    record Run(RuleAnswerService answers, LlmClient llm, RuleVectorStore.Match source,
               UUID game, UUID version, AnswerConversation context) { }
    private Run pipeline(Policy policy, Scenario scenario) {
        UUID game = UUID.randomUUID(), version = UUID.randomUUID();
        var source = new RuleVectorStore.Match(UUID.randomUUID(), UUID.randomUUID(), version, scenario.source(),
                "Original fictional rules", RuleSourceType.RULEBOOK, "1", "en", "Self-authored evaluation fixture",
                "Fixture section", 1, 0, 0, scenario.source().length(), scenario.score());
        var client = mock(EmbeddingClient.class);
        var profile = new EmbeddingProfile("fixture", "threshold-replay", 3);
        when(client.profile()).thenReturn(profile);
        when(client.embed(anyString())).thenReturn(new Embedding(List.of(1f, 0f, 0f)));
        var store = mock(RuleVectorStore.class);
        when(store.hasEmbeddings(eq(version), eq(profile), any())).thenReturn(true);
        when(store.search(eq(version), eq(profile), any(), anyInt(), any())).thenReturn(List.of(source));
        var gateway = new EmbeddingGateway(client, new EmbeddingProperties("disabled", "fixture", "", 3,
                Duration.ofSeconds(1), 4000, 100));
        var retrieval = new RuleRetrievalService(mock(RuleDocumentService.class), store, gateway,
                new RetrievalProperties(5, policy.retrieval(), 200, .85));
        var config = new AnswerProperties(policy.answer(), .8, 24000);
        var llm = mock(LlmClient.class);
        var answers = new RuleAnswerService(mock(GameService.class), retrieval,
                new RuleAnswerPromptBuilder(JSON, config), llm, config);
        var context = new AnswerConversation(UUID.randomUUID(), "Original game", "Fixture edition", "en", 3,
                "STANDARD", "SETUP", "Choose mission cards", SETUP,
                List.of(new AnswerConversation.Turn("USER", "Can I keep all four?")));
        return new Run(answers, llm, source, game, version, context);
    }

    @AfterAll static void writeReport() throws Exception {
        var output = Path.of("build", "reports", "threshold-evaluation.json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "description", "Offline real-service gate replay with fictional rules and provider doubles. Only the risk score is copied from a live log; other scores are synthetic. Not a live quality benchmark or threshold recommendation.",
                "semanticLimitation", "An incorrect answer with an exact but irrelevant quotation can pass existing validation even at baseline thresholds.",
                "productionConfigurationChanged", false, "cases", ROWS));
    }
}
