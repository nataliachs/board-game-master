package com.boardgamemaster.setup.domain;

import java.util.*;
import static com.boardgamemaster.setup.domain.SetupException.Code.INVALID_SETUP_ACTION;

/** A pinned flow may be present before setup starts; progress never comes from LLM messages. */
public record SetupProgress(UUID flowId, String currentStepKey, Set<String> completedStepKeys,
        String returnSectionKey, boolean completed) {
    public enum Action { NEXT, PREVIOUS, REPEAT }
    public SetupProgress {
        completedStepKeys = Set.copyOf(completedStepKeys);
        if (flowId == null || ((currentStepKey == null) != (returnSectionKey == null))
                || (currentStepKey == null && (completed || !completedStepKeys.isEmpty()))) { throw invalid("Invalid setup progress"); }
    }
    public static SetupProgress pinned(UUID id) { return new SetupProgress(id, null, Set.of(), null, false); }
    public SetupProgress start(SetupFlow flow, String returnKey) {
        requireFlow(flow);
        if (returnKey == null || returnKey.isBlank()) { throw invalid("A teaching return position is required"); }
        return new SetupProgress(flowId, flow.steps().getFirst().key(), Set.of(), returnKey, false);
    }
    public SetupProgress navigate(SetupFlow flow, Action action) {
        int index = index(flow);
        if (action == null) { throw invalid("Setup action is required"); }
        var done = new HashSet<>(completedStepKeys);
        if (action == Action.NEXT) {
            if (index == flow.steps().size() - 1) { throw invalid("Use complete to acknowledge the final setup step"); }
            done.add(currentStepKey); index++;
        } else if (action == Action.PREVIOUS && index > 0) { index--; }
        return new SetupProgress(flowId, flow.steps().get(index).key(), done, returnSectionKey, false);
    }
    public SetupProgress finish(SetupFlow flow) {
        int index = index(flow);
        if (index != flow.steps().size() - 1 || flow.steps().subList(0, index).stream().anyMatch(s -> !completedStepKeys.contains(s.key()))) {
            throw invalid("Acknowledge each setup step before completing setup");
        }
        var done = new HashSet<>(completedStepKeys); done.add(currentStepKey);
        return new SetupProgress(flowId, currentStepKey, done, returnSectionKey, true);
    }
    public int index(SetupFlow flow) {
        requireFlow(flow);
        if (completed || currentStepKey == null) { throw invalid("Setup is not in progress"); }
        for (int i = 0; i < flow.steps().size(); i++) { if (flow.steps().get(i).key().equals(currentStepKey)) { return i; } }
        throw invalid("Current step does not belong to the pinned setup flow");
    }
    private void requireFlow(SetupFlow flow) {
        if (flow == null || !flow.id().equals(flowId)) { throw invalid("Setup requires this session's pinned flow"); }
    }
    private static SetupException invalid(String message) { return new SetupException(INVALID_SETUP_ACTION, message); }
}
