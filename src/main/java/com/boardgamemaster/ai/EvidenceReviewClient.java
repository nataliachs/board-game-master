package com.boardgamemaster.ai;

import java.util.List;
import java.util.UUID;

/** Separate provider contracts for relevance selection and cited-answer support checking. */
public interface EvidenceReviewClient {
    List<UUID> selectEvidence(LlmRequest request);
    boolean supportsAnswer(LlmRequest request);
}
