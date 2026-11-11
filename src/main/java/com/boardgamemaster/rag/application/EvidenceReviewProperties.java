package com.boardgamemaster.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.evidence-review")
public record EvidenceReviewProperties(boolean enabled, Double candidateFloor, Integer candidateCount, Integer selectAbove) {
    /**
     * {@code selectAbove}: relevance selection runs only for a pool larger than this. A pool this small is no
     * more than an ordinary answer already receives, so the answer model reads all of it and one model call
     * is saved; the cited-answer support check still runs. 0 always selects.
     */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public EvidenceReviewProperties {
        candidateFloor = candidateFloor == null ? .35 : candidateFloor;
        candidateCount = candidateCount == null ? 12 : candidateCount;
        selectAbove = selectAbove == null ? 5 : selectAbove;
        if (!Double.isFinite(candidateFloor) || candidateFloor < 0 || candidateFloor > 1
                || candidateCount < 1 || candidateCount > 20 || selectAbove < 0 || selectAbove > 20) {
            throw new IllegalArgumentException("Invalid evidence review candidate floor, count or selection size");
        }
    }
    /** Always selects, whatever the pool size. */
    public EvidenceReviewProperties(boolean enabled, Double candidateFloor, Integer candidateCount) {
        this(enabled, candidateFloor, candidateCount, 0);
    }
}
