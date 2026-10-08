package com.boardgamemaster.ai;

/** Provider boundary; application code never depends on a vendor response envelope. */
public interface LlmClient {
    LlmResponse generate(LlmRequest request);
}
