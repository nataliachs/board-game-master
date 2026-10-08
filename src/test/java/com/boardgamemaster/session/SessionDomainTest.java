package com.boardgamemaster.session;

import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.session.application.SessionProperties;
import com.boardgamemaster.rag.application.AnswerConversation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class SessionDomainTest {
    private TeachingSection section(String key, int order, Set<TeachingDepth> depths) {
        return new TeachingSection(key, order, "Title " + key, "Original explanation", depths, List.of("An example"), null, null);
    }
    private TeachingFlow flow() {
        return new TeachingFlow(UUID.randomUUID(), UUID.randomUUID(), "Original flow", Instant.now(), List.of(
                section("LAST", 4, null), section("FIRST", 0, null), section("DETAIL", 2, Set.of(TeachingDepth.DETAILED))));
    }
    private GameSession session(TeachingFlow flow) {
        return new GameSession(UUID.randomUUID(), UUID.randomUUID(), flow.gameVersionId(), flow.id(), 3, TeachingDepth.STANDARD,
                SessionStage.TEACHING, GameSession.Status.ACTIVE, "FIRST", Set.of(), 0, Instant.now(), null);
    }
    @Test void orderedDepthSpecificImmutableTeachingContent() {
        var flow = flow();
        assertThat(flow.sectionsFor(TeachingDepth.STANDARD)).extracting(TeachingSection::key).containsExactly("FIRST", "LAST");
        assertThat(flow.sectionsFor(TeachingDepth.DETAILED)).extracting(TeachingSection::key).containsExactly("FIRST", "DETAIL", "LAST");
        assertThatThrownBy(() -> flow.sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> flow.sections().getFirst().depths().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void navigationTracksCompletionAndRetainsPinnedIdentifiers() {
        var flow = flow(); var original = session(flow);
        var next = original.navigate(flow, GameSession.Action.CONTINUE);
        assertThat(next.currentSectionKey()).isEqualTo("LAST");
        assertThat(next.completedSections()).containsExactly("FIRST");
        assertThat(next.revision()).isEqualTo(1);
        var previous = next.navigate(flow, GameSession.Action.PREVIOUS);
        assertThat(previous.currentSectionKey()).isEqualTo("FIRST");
        assertThat(previous.completedSections()).containsExactly("FIRST");
        assertThat(previous.id()).isEqualTo(original.id());
        assertThat(previous.gameVersionId()).isEqualTo(flow.gameVersionId());
        assertThat(previous.teachingFlowId()).isEqualTo(flow.id());
        assertThat(original.completedSections()).isEmpty();
    }
    @ParameterizedTest @EnumSource(value = GameSession.Action.class, names = {"PREVIOUS", "REPEAT", "EXPLAIN_AGAIN"})
    void nonadvancingControlsKeepFirstLesson(GameSession.Action action) {
        var flow = flow(); var result = session(flow).navigate(flow, action);
        assertThat(result.currentSectionKey()).isEqualTo("FIRST"); assertThat(result.completedSections()).isEmpty();
    }
    @Test void finishingEntersPlayingInSameSessionThenEndsExplicitly() {
        var flow = flow(); var original = session(flow);
        var playing = original.navigate(flow, GameSession.Action.CONTINUE).navigate(flow, GameSession.Action.CONTINUE);
        assertThat(playing.stage()).isEqualTo(SessionStage.PLAYING);
        assertThat(playing.status()).isEqualTo(GameSession.Status.ACTIVE);
        assertThat(playing.id()).isEqualTo(original.id());
        assertThat(playing.completedSections()).containsExactlyInAnyOrder("FIRST", "LAST");
        assertThatThrownBy(() -> playing.navigate(flow, GameSession.Action.PREVIOUS)).isInstanceOf(SessionException.class);
        var ended = playing.complete(Instant.now());
        assertThat(ended.stage()).isEqualTo(SessionStage.COMPLETED);
        assertThat(ended.status()).isEqualTo(GameSession.Status.COMPLETED);
        assertThat(ended.endedAt()).isNotNull();
        assertThatThrownBy(ended::requireActive).isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> ended.complete(Instant.now())).isInstanceOf(SessionException.class);
    }
    @Test void otherFlowsCannotControlSession() {
        var original = flow(); var session = session(original);
        assertThatThrownBy(() -> session.navigate(flow(), GameSession.Action.CONTINUE)).isInstanceOf(SessionException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"", "lower", "HAS SPACE", "1KEY"})
    void invalidSectionKeysRejected(String key) {
        assertThatThrownBy(() -> section(key, 0, null)).isInstanceOf(SessionException.class);
    }
    @Test void duplicateKeysOrdersMissingDepthsAndExcessiveContentRejected() {
        var one = section("ONE", 0, null);
        assertThatThrownBy(() -> new TeachingFlow(UUID.randomUUID(), UUID.randomUUID(), "Flow", Instant.now(), List.of(one, one)))
                .isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> new TeachingFlow(UUID.randomUUID(), UUID.randomUUID(), "Flow", Instant.now(),
                List.of(section("ONE", 0, Set.of(TeachingDepth.QUICK))))).isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> new TeachingSection("ONE", 0, "Title", "x".repeat(8001), null, null, null, null)).isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> section("ONE", 0, Set.of())).isInstanceOf(SessionException.class);
    }
    @Test void contextLimitsAreBoundedAndDefaulted() {
        assertThat(new SessionProperties(null, null).recentMessageLimit()).isEqualTo(8);
        assertThatThrownBy(() -> new SessionProperties(1, 8000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionProperties(51, 8000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionProperties(8, 999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionProperties(8, 16001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contextualQueryKeepsCurrentQuestionAndLatestUserTopicWithinEmbeddingLimit() {
        var conversation = new AnswerConversation(UUID.randomUUID(), "Game", "Edition", "en", 3, "STANDARD", "TEACHING", "Production", "Lesson",
                List.of(new AnswerConversation.Turn("USER", "Old topic"), new AnswerConversation.Turn("USER", "Resource collection"),
                        new AnswerConversation.Turn("ASSISTANT", "Assistant is not evidence")));
        assertThat(conversation.retrievalQuery("What if it is blocked?")).startsWith("What if it is blocked?").contains("Resource collection", "Production").doesNotContain("Old topic", "Assistant is not evidence");
        assertThat(conversation.retrievalQuery("x".repeat(4000))).hasSize(4000);
        assertThat(conversation.retrievalQuery("x".repeat(3980))).hasSize(4000);
    }
}
