package com.boardgamemaster.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Baseline answer acceptance gates, not retrieval filtering or calibrated probabilities. */
@ConfigurationProperties("boardgame.answer")
public record AnswerProperties(double minimumEvidenceSimilarity, double highEvidenceSimilarity, int maxContextCharacters, Integer minimumQuoteCharacters) {
    public AnswerProperties(double minimum, double high, int context) { this(minimum, high, context, null); }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AnswerProperties {
        minimumQuoteCharacters = minimumQuoteCharacters == null ? 12 : minimumQuoteCharacters;
        if (!Double.isFinite(minimumEvidenceSimilarity) || !Double.isFinite(highEvidenceSimilarity)
                || minimumEvidenceSimilarity < 0 || minimumEvidenceSimilarity >= highEvidenceSimilarity
                || highEvidenceSimilarity > 1 || maxContextCharacters < 1000 || maxContextCharacters > 160000
                || minimumQuoteCharacters < 1 || minimumQuoteCharacters > 1000) {
            throw new IllegalArgumentException("Invalid answer evidence or context limits");
        }
    }
}
