package com.boardgamemaster.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Baseline answer acceptance gates, not retrieval filtering or calibrated probabilities. */
@ConfigurationProperties("boardgame.answer")
public record AnswerProperties(double minimumEvidenceSimilarity, double highEvidenceSimilarity, int maxContextCharacters) {
    public AnswerProperties {
        if (!Double.isFinite(minimumEvidenceSimilarity) || !Double.isFinite(highEvidenceSimilarity)
                || minimumEvidenceSimilarity < 0 || minimumEvidenceSimilarity >= highEvidenceSimilarity
                || highEvidenceSimilarity > 1 || maxContextCharacters < 1000 || maxContextCharacters > 160000) {
            throw new IllegalArgumentException("Invalid answer evidence or context limits");
        }
    }
}
