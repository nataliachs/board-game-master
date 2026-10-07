package com.boardgamemaster.rules.api;

import com.boardgamemaster.rules.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class RuleResponses {
    private RuleResponses() { }

    public record Document(UUID id, UUID gameVersionId, String name, RuleSourceType sourceType,
            String version, String language, String sourceReference, String fileName, Instant importedAt,
            int chunkSize, int chunkOverlap, int chunkCount) {
        static Document from(RuleDocument d) {
            return new Document(d.getId(), d.getGameVersionId(), d.getName(), d.getSourceType(), d.getVersion(),
                    d.getLanguage(), d.getSourceReference(), d.getFileName(), d.getImportedAt(),
                    d.getChunkSize(), d.getChunkOverlap(), d.getChunkCount());
        }
    }
    public record Chunk(UUID id, UUID documentId, UUID gameVersionId, String content,
            String sectionTitle, Integer pageNumber, int chunkIndex, int startOffset, int endOffset) {
        static Chunk from(RuleChunk c) {
            return new Chunk(c.getId(), c.getDocumentId(), c.getGameVersionId(), c.getContent(),
                    c.getSectionTitle(), c.getPageNumber(), c.getChunkIndex(), c.getStartOffset(), c.getEndOffset());
        }
    }
    public record Source(UUID documentId, UUID gameVersionId, String text) { }
    public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        static <T> PageResult<T> from(Page<T> page) {
            return new PageResult<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
