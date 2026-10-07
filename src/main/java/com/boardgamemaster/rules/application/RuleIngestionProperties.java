package com.boardgamemaster.rules.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.rules")
public record RuleIngestionProperties(int chunkSize, int chunkOverlap, int maxBytes, int maxChunks) {
    public RuleIngestionProperties {
        if (chunkSize < 64 || chunkSize > 16000 || chunkOverlap < 0 || chunkOverlap >= chunkSize
                || maxBytes < 1 || maxBytes > 10 * 1024 * 1024 || maxChunks < 1 || maxChunks > 100000) {
            throw new IllegalArgumentException("Invalid rule ingestion limits: chunk size 64..16000, overlap 0..size-1, bytes 1..10485760, chunks 1..100000");
        }
    }
}
