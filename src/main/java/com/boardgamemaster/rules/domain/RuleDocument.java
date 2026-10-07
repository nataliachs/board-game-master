package com.boardgamemaster.rules.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rule_documents")
public class RuleDocument {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private UUID gameVersionId;
    @Column(nullable = false, length = 200) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RuleSourceType sourceType;
    @Column(nullable = false, length = 100) private String version;
    @Column(nullable = false, length = 35) private String language;
    @Column(nullable = false, length = 1000) private String sourceReference;
    @Column(nullable = false, length = 255) private String fileName;
    @Column(nullable = false) private Instant importedAt;
    @Column(nullable = false, columnDefinition = "text") private String normalizedText;
    @Column(nullable = false) private int chunkSize;
    @Column(nullable = false) private int chunkOverlap;
    @Column(nullable = false) private int chunkCount;

    protected RuleDocument() { }

    public RuleDocument(UUID gameVersionId, DocumentMetadata metadata, String fileName,
            String normalizedText, int chunkSize, int chunkOverlap, int chunkCount) {
        this.gameVersionId = gameVersionId;
        this.name = metadata.name();
        this.sourceType = metadata.sourceType();
        this.version = metadata.version();
        this.language = metadata.language();
        this.sourceReference = metadata.sourceReference();
        this.fileName = fileName;
        this.normalizedText = normalizedText;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.chunkCount = chunkCount;
        this.importedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getGameVersionId() { return gameVersionId; }
    public String getName() { return name; }
    public RuleSourceType getSourceType() { return sourceType; }
    public String getVersion() { return version; }
    public String getLanguage() { return language; }
    public String getSourceReference() { return sourceReference; }
    public String getFileName() { return fileName; }
    public Instant getImportedAt() { return importedAt; }
    public String getNormalizedText() { return normalizedText; }
    public int getChunkSize() { return chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public int getChunkCount() { return chunkCount; }
}
