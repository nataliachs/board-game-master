package com.boardgamemaster.rules.infrastructure;

import com.boardgamemaster.rules.domain.RuleDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleDocumentRepository extends JpaRepository<RuleDocument, UUID> {
    Optional<RuleDocument> findByIdAndGameVersionId(UUID id, UUID gameVersionId);
    Page<RuleDocument> findByGameVersionId(UUID gameVersionId, Pageable pageable);
}
