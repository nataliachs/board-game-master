package com.boardgamemaster.rules.infrastructure;

import com.boardgamemaster.rules.domain.RuleChunk;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleChunkRepository extends JpaRepository<RuleChunk, UUID> {
    Page<RuleChunk> findByDocumentIdAndGameVersionId(UUID documentId, UUID gameVersionId, Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM RuleChunk c WHERE c.documentId = :documentId AND c.gameVersionId = :gameVersionId")
    int deleteByDocumentIdAndGameVersionId(@org.springframework.data.repository.query.Param("documentId") UUID documentId,
            @org.springframework.data.repository.query.Param("gameVersionId") UUID gameVersionId);
}
