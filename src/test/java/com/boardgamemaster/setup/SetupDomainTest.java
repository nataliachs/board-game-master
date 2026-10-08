package com.boardgamemaster.setup;

import com.boardgamemaster.setup.domain.*;
import com.boardgamemaster.session.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class SetupDomainTest {
    private SetupStep step(String key, int order) { return new SetupStep(key, order, "Title", "Original instruction", null, null); }
    private SetupFlow flow(List<SetupStep> steps) { return new SetupFlow(UUID.randomUUID(), UUID.randomUUID(), "Flow", Instant.now(), steps); }
    private SetupFlow flow() { return flow(List.of(step("LAST", 3), step("FIRST", 0))); }
    private GameSession session(SetupFlow f) {
        return new GameSession(UUID.randomUUID(), UUID.randomUUID(), f.gameVersionId(), UUID.randomUUID(), 3,
                TeachingDepth.STANDARD, SessionStage.TEACHING, GameSession.Status.ACTIVE, "LESSON", Set.of("INTRO"), 0, Instant.now(), null, SetupProgress.pinned(f.id()));
    }
    @Test void sortsAndFreezesAuthoredContent() {
        var f = flow(); assertThat(f.steps()).extracting(SetupStep::key).containsExactly("FIRST", "LAST");
        assertThatThrownBy(() -> f.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(f.steps().getFirst().warnings()).isEmpty();
        var notes = new ArrayList<>(List.of("A note"));
        var s = new SetupStep("ONE", 0, "Title", "Instruction", notes, null); notes.clear();
        assertThat(s.notes()).containsExactly("A note");
        assertThatThrownBy(() -> s.notes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "lower", "1KEY", "BAD KEY"})
    void invalidKeysRejected(String key) { assertThatThrownBy(() -> step(key, 0)).isInstanceOf(SetupException.class); }
    @Test void boundsAndDuplicateFieldsRejected() {
        assertThatThrownBy(() -> step("ONE", -1)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> new SetupStep("ONE", 0, "Title", "x".repeat(8001), null, null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> new SetupStep("ONE", 0, "Title", "Instruction", Collections.nCopies(11, "note"), null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> new SetupStep("ONE", 0, "Title", "Instruction", List.of(" "), null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> new SetupStep("ONE", 0, "Title", "Instruction", null, List.of("x".repeat(2001)))).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> flow(List.of())).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> flow(Collections.nCopies(101, step("ONE", 0)))).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> flow(List.of(step("ONE", 0), step("ONE", 1)))).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> flow(List.of(step("ONE", 0), step("TWO", 0)))).isInstanceOf(SetupException.class);
    }
    @Test void setupNavigationBoundariesAndCompletionReturnToTeaching() {
        var f = flow(); var initial = session(f); var started = initial.startSetup(f, false);
        assertThat(started.stage()).isEqualTo(SessionStage.SETUP);
        assertThat(started.setup().returnSectionKey()).isEqualTo("LESSON");
        assertThat(started.completedSections()).isEqualTo(initial.completedSections());
        assertThat(started.navigateSetup(f, SetupProgress.Action.PREVIOUS).setup()).isEqualTo(started.setup());
        assertThat(started.navigateSetup(f, SetupProgress.Action.REPEAT).setup()).isEqualTo(started.setup());
        assertThatThrownBy(() -> started.finishSetup(f)).isInstanceOf(SetupException.class);
        var last = started.navigateSetup(f, SetupProgress.Action.NEXT);
        assertThat(last.setup().completedStepKeys()).containsExactly("FIRST");
        assertThatThrownBy(() -> last.navigateSetup(f, SetupProgress.Action.NEXT)).isInstanceOf(SetupException.class);
        var done = last.finishSetup(f);
        assertThat(done.stage()).isEqualTo(SessionStage.TEACHING); assertThat(done.currentSectionKey()).isEqualTo("LESSON");
        assertThat(done.setup().completed()).isTrue();
        assertThat(done.setup().completedStepKeys()).containsExactlyInAnyOrder("FIRST", "LAST");
        assertThatThrownBy(() -> done.finishSetup(f)).isInstanceOf(SessionException.class);
        var restart = done.startSetup(f, true);
        assertThat(restart.setup().completed()).isFalse(); assertThat(restart.setup().completedStepKeys()).isEmpty();
        assertThat(restart.setup().currentStepKey()).isEqualTo("FIRST");
    }
    @Test void cannotSkipRequiredStepsWithForgedProgress() {
        var f = flow(); var progress = new SetupProgress(f.id(), "LAST", Set.of(), "LESSON", false);
        assertThatThrownBy(() -> progress.finish(f)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> new SetupProgress(f.id(), null, Set.of(), "LESSON", false)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> new SetupProgress(f.id(), null, Set.of("FIRST"), null, false)).isInstanceOf(SetupException.class);
    }
    @Test void setupCannotBeControlledByForeignFlowOrInactiveSession() {
        var f = flow(); var s = session(f);
        assertThatThrownBy(() -> s.startSetup(flow(), false)).isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> s.startSetup(new SetupFlow(UUID.randomUUID(), f.gameVersionId(), "Other", Instant.now(), f.steps()), false)).isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> s.startSetup(f, true)).isInstanceOf(SessionException.class);
        var started = s.startSetup(f, false);
        assertThatThrownBy(() -> started.startSetup(f, false)).isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> started.complete(Instant.now()).navigateSetup(f, SetupProgress.Action.NEXT)).isInstanceOf(SessionException.class);
        assertThatThrownBy(() -> s.navigateSetup(f, SetupProgress.Action.NEXT)).isInstanceOf(SessionException.class);
    }
    @Test void singleStepSetupCompletesWithoutNextAndTriggerCannotBeBypassed() {
        var f = flow(List.of(step("ONLY", 0))); var s = session(f);
        var trigger = new TeachingSection("LESSON", 0, "Title", "Explanation", null, null, null, null, true);
        var teaching = new TeachingFlow(s.teachingFlowId(), s.gameVersionId(), "Teaching", Instant.now(), List.of(trigger));
        assertThatThrownBy(() -> s.navigate(teaching, GameSession.Action.CONTINUE)).isInstanceOf(SessionException.class);
        var resumed = s.startSetup(f, false).finishSetup(f);
        assertThat(resumed.stage()).isEqualTo(SessionStage.TEACHING);
        assertThat(resumed.navigate(teaching, GameSession.Action.CONTINUE).stage()).isEqualTo(SessionStage.PLAYING);
        assertThatThrownBy(() -> resumed.navigate(teaching, GameSession.Action.CONTINUE).startSetup(f, true)).isInstanceOf(SessionException.class);
    }
}
