package com.boardgamemaster.setup.domain;

import java.time.Instant;
import java.util.*;
import static com.boardgamemaster.setup.domain.SetupException.Code.INVALID_SETUP_FLOW;

public record SetupFlow(UUID id, UUID gameVersionId, String name, Instant createdAt, List<SetupStep> steps) {
    public SetupFlow {
        if (id == null || gameVersionId == null || createdAt == null || name == null || name.isBlank() || name.length() > 200
                || steps == null || steps.isEmpty() || steps.size() > 100 || steps.stream().anyMatch(Objects::isNull)) { throw invalid(); }
        name = name.strip();
        steps = steps.stream().sorted(Comparator.comparingInt(SetupStep::order)).toList();
        if (steps.stream().map(SetupStep::key).distinct().count() != steps.size()
                || steps.stream().map(SetupStep::order).distinct().count() != steps.size()) { throw invalid(); }
    }
    private static SetupException invalid() { return new SetupException(INVALID_SETUP_FLOW, "A setup flow needs 1–100 uniquely keyed and ordered steps"); }
}
