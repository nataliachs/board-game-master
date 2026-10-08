package com.boardgamemaster.rag.application;

import java.util.List;

/** Reorders only supplied candidates. Implementations must retain original scores/provenance. */
public interface RuleReranker {
    List<RuleVectorStore.Match> rerank(String query, List<RuleVectorStore.Match> candidates);
}
