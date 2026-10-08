package com.boardgamemaster.rag.application;

import com.boardgamemaster.rules.domain.RuleSourceType;
import java.util.List;
import java.util.UUID;

public record RuleAnswer(UUID gameId, UUID gameVersionId, String answer, Confidence confidence, List<Source> sources) {
    public enum Confidence { HIGH, MEDIUM, LOW }
    public record Source(UUID chunkId, UUID documentId, UUID gameVersionId, String document, RuleSourceType sourceType,
            String documentVersion, String language, String sourceReference, String section, Integer page,
            int chunkIndex, int startOffset, int endOffset, String quote, double cosineSimilarity) { }
    public RuleAnswer { sources = List.copyOf(sources); }
}
