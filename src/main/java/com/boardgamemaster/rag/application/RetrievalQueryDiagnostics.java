package com.boardgamemaster.rag.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit local opt-in: logs the effective search query, never provider bodies or rule text. */
final class RetrievalQueryDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger("com.boardgamemaster.rag.querydiagnostics");
    private static final ObjectMapper JSON = new ObjectMapper();
    private RetrievalQueryDiagnostics() { }

    static void log(UUID versionId, String query, List<RuleVectorStore.Match> candidates, double threshold) {
        if (!LOG.isDebugEnabled()) { return; }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("gameVersionId", versionId);
        detail.put("query", prefix(query, 1000));
        detail.put("queryCharacters", query.length());
        detail.put("queryTruncated", query.length() > 1000);
        detail.put("similarityThreshold", threshold);
        detail.put("candidateCount", candidates.size());
        detail.put("candidatesTruncated", candidates.size() > 500);
        detail.put("candidates", candidates.stream().limit(500).map(candidate -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("chunkId", candidate.chunkId());
            item.put("documentId", candidate.documentId());
            item.put("section", candidate.sectionTitle() == null ? null : prefix(candidate.sectionTitle(), 160));
            item.put("cosineSimilarity", candidate.cosineSimilarity());
            item.put("passesThreshold", Double.isFinite(candidate.cosineSimilarity()) && candidate.cosineSimilarity() >= threshold);
            return item;
        }).toList());
        try { LOG.debug("Rule retrieval query detail: {}", JSON.writeValueAsString(detail)); }
        catch (JsonProcessingException ignored) { LOG.warn("Rule retrieval query detail unavailable: gameVersionId={}", versionId); }
    }

    private static String prefix(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) { end--; }
        return text.substring(0, end);
    }
}
