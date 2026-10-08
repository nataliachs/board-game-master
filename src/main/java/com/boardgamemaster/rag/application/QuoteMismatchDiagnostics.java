package com.boardgamemaster.rag.application;

import com.boardgamemaster.ai.LlmResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit local opt-in: source text must never appear in ordinary pipeline logs. */
final class QuoteMismatchDiagnostics {
    static final String LOGGER_NAME = "com.boardgamemaster.rag.quotediagnostics";
    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CONTENT_CHARACTERS = 8000;

    private QuoteMismatchDiagnostics() { }

    static void log(List<RuleVectorStore.Match> evidence, RuleVectorStore.Match match,
            LlmResponse.Citation citation, int attempt) {
        if (!LOG.isDebugEnabled()) { return; }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("attempt", attempt);
        detail.put("gameVersionId", match.gameVersionId());
        detail.put("chunkId", match.chunkId());
        detail.put("documentId", match.documentId());
        detail.put("quote", citation.quote());
        int end = Math.min(MAX_CONTENT_CHARACTERS, match.content().length());
        if (end > 0 && end < match.content().length() && Character.isHighSurrogate(match.content().charAt(end - 1))) { end--; }
        detail.put("citedContent", match.content().substring(0, end));
        detail.put("contentCharacters", match.content().length());
        detail.put("contentTruncated", end < match.content().length());
        detail.put("exactQuoteFoundInOtherChunkIds", evidence.stream()
                .filter(other -> !other.chunkId().equals(match.chunkId()) && other.content().contains(citation.quote()))
                .map(RuleVectorStore.Match::chunkId).toList());
        try {
            // JSON preserves whitespace distinctions while escaping control characters in the console.
            LOG.debug("Rule quote mismatch detail: {}", JSON.writeValueAsString(detail));
        } catch (JsonProcessingException ignored) {
            LOG.warn("Rule quote mismatch detail unavailable: chunkId={}", match.chunkId());
        }
    }
}
