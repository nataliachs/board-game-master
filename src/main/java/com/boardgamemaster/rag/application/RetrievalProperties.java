package com.boardgamemaster.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.rag")
public record RetrievalProperties(Integer topK) {
    public RetrievalProperties {
        topK = topK == null ? 5 : topK;
        if (topK < 1 || topK > 50) { throw new IllegalArgumentException("Retrieval top-k must be between 1 and 50"); }
    }
}
