package com.boardgamemaster.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.evidence-review")
public record EvidenceReviewProperties(boolean enabled, Double candidateFloor, Integer candidateCount) {
    public EvidenceReviewProperties {
        candidateFloor = candidateFloor == null ? .35 : candidateFloor;
        candidateCount = candidateCount == null ? 12 : candidateCount;
        if (!Double.isFinite(candidateFloor) || candidateFloor < 0 || candidateFloor > 1
                || candidateCount < 1 || candidateCount > 20) {
            throw new IllegalArgumentException("Invalid evidence review candidate floor or count");
        }
    }
}
