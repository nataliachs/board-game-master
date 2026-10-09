package com.boardgamemaster.ai;

import java.util.Optional;

/** Provider boundary for standalone search queries, never answers or rule evidence. */
@FunctionalInterface
public interface QueryRewriteClient {
    Optional<String> rewrite(LlmRequest request);
}
