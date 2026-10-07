package com.boardgamemaster.ai;

/** Provider-neutral boundary: no SDK or HTTP types cross it. */
public interface EmbeddingClient {
    EmbeddingProfile profile();
    Embedding embed(String text);
}
