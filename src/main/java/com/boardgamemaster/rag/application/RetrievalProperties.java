package com.boardgamemaster.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.rag")
public record RetrievalProperties(Integer topK, Double similarityThreshold, Integer candidateLimit, Double duplicateOverlap) {
    public RetrievalProperties(Integer topK) { this(topK, null, null, null); }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RetrievalProperties {
        topK = topK == null ? 5 : topK;
        similarityThreshold = similarityThreshold == null ? .5 : similarityThreshold;
        candidateLimit = candidateLimit == null ? 200 : candidateLimit;
        duplicateOverlap = duplicateOverlap == null ? .85 : duplicateOverlap;
        if (topK < 1 || topK > 50 || !Double.isFinite(similarityThreshold) || similarityThreshold < -1 || similarityThreshold > 1
                || candidateLimit < 50 || candidateLimit > 500 || !Double.isFinite(duplicateOverlap) || duplicateOverlap < .5 || duplicateOverlap > 1) {
            throw new IllegalArgumentException("Invalid retrieval top-k, similarity threshold, candidate limit or duplicate overlap");
        }
    }
}
