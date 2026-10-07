package com.boardgamemaster.rag.infrastructure;

import com.boardgamemaster.ai.Embedding;
import com.boardgamemaster.ai.EmbeddingProfile;
import com.boardgamemaster.rag.application.*;
import com.boardgamemaster.rules.domain.RuleSourceType;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PgVectorRuleStore implements RuleVectorStore {
    private final JdbcTemplate jdbc;
    public PgVectorRuleStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Counts counts(UUID versionId, UUID documentId, EmbeddingProfile p) {
        return jdbc.queryForObject("""
                SELECT count(*) AS total, count(e.chunk_id) AS indexed FROM rule_chunks c
                LEFT JOIN rule_chunk_embeddings e ON e.chunk_id=c.id AND e.game_version_id=c.game_version_id
                    AND e.provider=? AND e.model=? AND e.dimensions=?
                WHERE c.game_version_id=? AND c.document_id=?
                """, (r, n) -> new Counts(r.getInt("total"), r.getInt("indexed")),
                p.provider(), p.model(), p.dimensions(), versionId, documentId);
    }

    @Override public List<ChunkText> pending(UUID versionId, UUID documentId, EmbeddingProfile p) {
        return jdbc.query("""
                SELECT c.id,c.content FROM rule_chunks c
                LEFT JOIN rule_chunk_embeddings e ON e.chunk_id=c.id AND e.game_version_id=c.game_version_id
                    AND e.provider=? AND e.model=? AND e.dimensions=?
                WHERE c.game_version_id=? AND c.document_id=? AND e.chunk_id IS NULL ORDER BY c.chunk_index
                """, (r, n) -> new ChunkText(r.getObject("id", UUID.class), r.getString("content")),
                p.provider(), p.model(), p.dimensions(), versionId, documentId);
    }

    @Override @Transactional
    public int store(UUID versionId, UUID documentId, EmbeddingProfile p, List<VectorWrite> writes) {
        // Lock only for the short database write, never while calling a remote provider.
        var document = jdbc.queryForList("SELECT id FROM rule_documents WHERE id=? AND game_version_id=? FOR UPDATE",
                UUID.class, documentId, versionId);
        if (document.isEmpty()) { throw new RetrievalException(RetrievalException.Code.INDEX_CONFLICT, "Document changed during indexing"); }
        int inserted = 0;
        for (var write : writes) {
            write.embedding().requireDimensions(p.dimensions());
            int affected = jdbc.update("""
                    INSERT INTO rule_chunk_embeddings (chunk_id,game_version_id,provider,model,dimensions,embedding)
                    SELECT c.id,c.game_version_id,?,?,?,CAST(? AS vector) FROM rule_chunks c
                    WHERE c.id=? AND c.document_id=? AND c.game_version_id=?
                    ON CONFLICT (chunk_id,provider,model,dimensions) DO NOTHING
                    """, p.provider(), p.model(), p.dimensions(), literal(write.embedding()), write.chunkId(), documentId, versionId);
            if (affected == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM rule_chunk_embeddings e JOIN rule_chunks c ON c.id=e.chunk_id
                        WHERE c.id=? AND c.document_id=? AND c.game_version_id=? AND e.provider=? AND e.model=? AND e.dimensions=?)
                    """, Boolean.class, write.chunkId(), documentId, versionId, p.provider(), p.model(), p.dimensions()))) {
                throw new RetrievalException(RetrievalException.Code.INDEX_CONFLICT, "Rule chunks changed during indexing");
            }
            inserted += affected;
        }
        return inserted;
    }

    @Override public boolean hasEmbeddings(UUID versionId, EmbeddingProfile p) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM rule_chunk_embeddings WHERE game_version_id=? AND provider=? AND model=? AND dimensions=?)
                """, Boolean.class, versionId, p.provider(), p.model(), p.dimensions()));
    }

    @Override public List<Match> search(UUID versionId, EmbeddingProfile p, Embedding query, int limit) {
        query.requireDimensions(p.dimensions());
        return jdbc.query("""
                WITH candidates AS MATERIALIZED (
                    SELECT chunk_id, embedding FROM rule_chunk_embeddings
                    WHERE game_version_id=? AND provider=? AND model=? AND dimensions=?
                )
                SELECT c.*,d.name AS document_name,d.source_type,d.version AS document_version,d.language,d.source_reference,
                    1 - (e.embedding <=> CAST(? AS vector)) AS similarity
                FROM candidates e JOIN rule_chunks c ON c.id=e.chunk_id
                JOIN rule_documents d ON d.id=c.document_id AND d.game_version_id=c.game_version_id
                WHERE c.game_version_id=?
                ORDER BY similarity DESC,c.document_id,c.chunk_index,c.id LIMIT ?
                """, (r, n) -> new Match(r.getObject("id", UUID.class), r.getObject("document_id", UUID.class),
                r.getObject("game_version_id", UUID.class), r.getString("content"), r.getString("document_name"),
                RuleSourceType.valueOf(r.getString("source_type")), r.getString("document_version"), r.getString("language"),
                r.getString("source_reference"), r.getString("section_title"), r.getObject("page_number", Integer.class),
                r.getInt("chunk_index"), r.getInt("start_offset"), r.getInt("end_offset"),
                Math.max(-1, Math.min(1, r.getDouble("similarity")))),
                versionId, p.provider(), p.model(), p.dimensions(), literal(query), versionId, limit);
    }

    private static String literal(Embedding value) {
        return value.values().stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
    }
}
