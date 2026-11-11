package com.boardgamemaster.rag;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.rag.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextualQueryRewriterTest {
    @ParameterizedTest @ValueSource(strings = {"What's the risk?", "What's the downside?"})
    void rewritePromptRequestsConsequenceTermsWithoutGameFramingOrInventedRules(String question) throws Exception {
        String query = "What consequences or penalties can result from keeping all four starting mission cards?";
        when(client.rewrite(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            assertThat(request.instructions()).contains("do not merely repeat", "Omit game names", "search already filters by game version",
                    "may have consequences later", "Never specify an unsupported penalty", "Preserve explicit quantities");
            assertThat(mapper.readTree(request.input()).path("question").asText()).isEqualTo(question);
            return Optional.of(query);
        });
        // The double checks the delivered prompt/returned cue; real-model rewriting still needs evaluation.
        assertThat(rewriter(true).rewrite(question, context, 4000)).contains(query);
        verify(client, times(1)).rewrite(any());
    }
    private final QueryRewriteClient client = mock(QueryRewriteClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnswerConversation context = new AnswerConversation(UUID.randomUUID(), "Original game", "Edition", "en",
            3, "STANDARD", "SETUP", "Choose missions", "Authored lesson is not evidence.",
            List.of(new AnswerConversation.Turn("USER", "Can I keep all four mission cards?"),
                    new AnswerConversation.Turn("ASSISTANT", "Invented penalty of ninety points.")),
            new AnswerConversation.SetupContext("MISSIONS", "Choose starting mission cards", "Authored instruction is not evidence."));
    private ContextualQueryRewriter rewriter(boolean enabled) {
        return new ContextualQueryRewriter(client, mapper, new QueryRewriteProperties(enabled));
    }

    @Test void rewriteKeepsOnlyTheUsersQuestionAndThePromptForbidsAddingOne() {
        // Exploding Kittens, live: the appended penalties question made the answer step withhold a plain "No".
        when(client.rewrite(any())).thenAnswer(invocation -> {
            assertThat(((LlmRequest) invocation.getArgument(0)).instructions()).contains("Ask exactly one question",
                    "ONLY when the current question itself asks", "must not gain one");
            return Optional.of("Can a Nope card be played to cancel a Defuse card? What are the consequences or penalties,"
                    + " if any, of attempting to Nope a Defuse card?");
        });
        assertThat(rewriter(true).rewrite("Can I Nope a Defuse?", context, 4000))
                .contains("Can a Nope card be played to cancel a Defuse card?");
        doReturn(Optional.of("Returning unwanted starting mission cards")).when(client).rewrite(any());
        assertThat(rewriter(true).rewrite("Can I return them?", context, 4000)).contains("Returning unwanted starting mission cards");
    }

    @Test void disabledLongAndNoHistoryQuestionsAvoidProviderCalls() {
        assertThat(rewriter(false).rewrite("What's the risk?", context, 4000)).isEmpty();
        assertThat(rewriter(true).rewrite("q".repeat(81), context, 4000)).isEmpty();
        var noHistory = new AnswerConversation(context.sessionId(), context.game(), context.edition(), context.language(),
                3, "STANDARD", "SETUP", context.lessonTitle(), context.lessonExplanation(), List.of(), context.setup());
        assertThat(rewriter(true).rewrite("What's the risk?", noHistory, 4000)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test void referenceResolutionUsesOnlyUserTopicsAndTitlesAndPreservesOriginalContext() throws Exception {
        String query = "What are the consequences of keeping all four starting mission cards?";
        when(client.rewrite(any())).thenAnswer(invocation -> {
            LlmRequest input = invocation.getArgument(0);
            assertThat(input.instructions()).contains("Never answer", "Never", "search cue, never rule evidence");
            var data = mapper.readTree(input.input());
            assertThat(data.path("question").asText()).isEqualTo("What's the risk?");
            assertThat(data.path("previousUserQuestions").get(0).asText()).isEqualTo("Can I keep all four mission cards?");
            assertThat(input.input()).doesNotContain("ninety points", "Authored lesson", "Authored instruction");
            return Optional.of(query);
        });
        assertThat(rewriter(true).rewrite("What's the risk?", context, 4000)).contains(query);
        assertThat(context.recentMessages().getLast().content()).contains("ninety points");
        verify(client, times(1)).rewrite(any());
    }

    @ParameterizedTest @EnumSource(LlmException.Code.class)
    void providerFailureFallsBackWithoutRetry(LlmException.Code code) {
        when(client.rewrite(any())).thenThrow(new LlmException(code, "private upstream body"));
        assertThat(rewriter(true).rewrite("What's the risk?", context, 4000)).isEmpty();
        verify(client, times(1)).rewrite(any());
    }

    @Test void unresolvedOversizedAndMalformedRewriteResultsFallBack() {
        when(client.rewrite(any())).thenReturn(Optional.empty()).thenReturn(Optional.of("x".repeat(1001)))
                .thenReturn(Optional.of(" ")).thenReturn(Optional.of("bad\nquery"))
                .thenReturn(Optional.of("q".repeat(101))).thenReturn(null);
        for (int i = 0; i < 6; i++) {
            assertThat(rewriter(true).rewrite("What's the risk?", context, 100)).isEmpty();
        }
    }
}
