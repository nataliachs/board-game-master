package com.boardgamemaster.session.domain;

import java.time.Instant;
import java.util.*;
import static com.boardgamemaster.session.domain.SessionException.Code.INVALID_TEACHING_FLOW;

public record TeachingFlow(UUID id, UUID gameVersionId, String name, Instant createdAt, List<TeachingSection> sections) {
    public TeachingFlow {
        if (id == null || gameVersionId == null || createdAt == null || name == null || name.isBlank()
                || name.length() > 200 || sections == null || sections.isEmpty() || sections.size() > 100
                || sections.stream().anyMatch(Objects::isNull)) { throw invalid(); }
        name = name.strip();
        sections = sections.stream().sorted(Comparator.comparingInt(TeachingSection::order)).toList();
        if (sections.stream().map(TeachingSection::key).distinct().count() != sections.size()
                || sections.stream().map(TeachingSection::order).distinct().count() != sections.size()) { throw invalid(); }
        for (var depth : TeachingDepth.values()) {
            if (sections.stream().noneMatch(s -> s.depths().contains(depth))) { throw invalid(); }
        }
    }
    public List<TeachingSection> sectionsFor(TeachingDepth depth) {
        return sections.stream().filter(s -> s.depths().contains(depth)).toList();
    }
    private static SessionException invalid() {
        return new SessionException(INVALID_TEACHING_FLOW, "A flow needs unique ordered sections and content for every teaching depth");
    }
}
