package com.boardgamemaster.rag.application;

import com.boardgamemaster.ai.Embedding;
import com.boardgamemaster.ai.EmbeddingProfile;
import com.boardgamemaster.rules.domain.RuleSourceType;
import java.util.List;
import java.util.UUID;

/** Storage port: vector SQL/serialization stay in infrastructure. */
public interface RuleVectorStore {
    record ChunkText(UUID id, String content) { }
    record VectorWrite(UUID chunkId, Embedding embedding) { }
    record Counts(int totalChunks, int indexedChunks) { }
    record Match(UUID chunkId, UUID documentId, UUID gameVersionId, String content, String documentName,
            RuleSourceType sourceType, String documentVersion, String language, String sourceReference,
            String sectionTitle, Integer pageNumber, int chunkIndex, int startOffset, int endOffset,
            double cosineSimilarity) { }

    Counts counts(UUID versionId, UUID documentId, EmbeddingProfile profile);
    List<ChunkText> pending(UUID versionId, UUID documentId, EmbeddingProfile profile);
    int store(UUID versionId, UUID documentId, EmbeddingProfile profile, List<VectorWrite> writes);
    boolean hasEmbeddings(UUID versionId, EmbeddingProfile profile);
    List<Match> search(UUID versionId, EmbeddingProfile profile, Embedding query, int limit);
    boolean hasEmbeddings(UUID versionId, EmbeddingProfile profile, RetrievalFilters filters);
    List<Match> search(UUID versionId, EmbeddingProfile profile, Embedding query, int limit, RetrievalFilters filters);
}
