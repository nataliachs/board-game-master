package com.boardgamemaster.rules.infrastructure;

import com.boardgamemaster.rules.domain.RuleChunk;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleChunkRepository extends JpaRepository<RuleChunk, UUID> {
    Page<RuleChunk> findByDocumentIdAndGameVersionId(UUID documentId, UUID gameVersionId, Pageable pageable);
}
