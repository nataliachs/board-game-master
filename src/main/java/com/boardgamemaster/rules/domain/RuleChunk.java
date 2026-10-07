package com.boardgamemaster.rules.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "rule_chunks")
public class RuleChunk {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private UUID gameVersionId;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Column(length = 300) private String sectionTitle;
    private Integer pageNumber;
    @Column(nullable = false) private int chunkIndex;
    @Column(nullable = false) private int startOffset;
    @Column(nullable = false) private int endOffset;

    protected RuleChunk() { }

    public RuleChunk(UUID documentId, UUID gameVersionId, String content, String sectionTitle,
            Integer pageNumber, int chunkIndex, int startOffset, int endOffset) {
        this.documentId = documentId;
        this.gameVersionId = gameVersionId;
        this.content = content;
        this.sectionTitle = sectionTitle;
        this.pageNumber = pageNumber;
        this.chunkIndex = chunkIndex;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getGameVersionId() { return gameVersionId; }
    public String getContent() { return content; }
    public String getSectionTitle() { return sectionTitle; }
    public Integer getPageNumber() { return pageNumber; }
    public int getChunkIndex() { return chunkIndex; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
}
