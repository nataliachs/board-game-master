package com.boardgamemaster.ai;

import java.util.List;
import java.util.UUID;

public record LlmResponse(String answer, boolean sufficientEvidence, List<Citation> citations) {
    public record Citation(UUID chunkId, String quote) { }
    public LlmResponse {
        if (answer == null || answer.length() > 8000 || citations == null || citations.size() > 50
                || (sufficientEvidence && (answer.isBlank() || citations.isEmpty()))
                || citations.stream().anyMatch(c -> c == null || c.chunkId() == null || c.quote() == null
                    || c.quote().isBlank() || c.quote().length() > 1000)) {
            throw new LlmException(LlmException.Code.LLM_INVALID_RESPONSE, "LLM provider returned an invalid answer contract");
        }
        citations = List.copyOf(citations);
    }
    @Override public String toString() { return "LlmResponse[content=REDACTED]"; }
}
