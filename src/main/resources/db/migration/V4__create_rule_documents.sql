CREATE TABLE rule_documents (
    id UUID PRIMARY KEY,
    game_version_id UUID NOT NULL REFERENCES game_versions(id),
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    source_type VARCHAR(20) NOT NULL CHECK (source_type IN ('RULEBOOK', 'FAQ', 'ERRATA', 'EXPANSION')),
    version VARCHAR(100) NOT NULL CHECK (btrim(version) <> ''),
    language VARCHAR(35) NOT NULL CHECK (language ~ '^[a-z]{2,3}(-[a-z0-9]{2,8})*$'),
    source_reference VARCHAR(1000) NOT NULL CHECK (btrim(source_reference) <> ''),
    file_name VARCHAR(255) NOT NULL CHECK (btrim(file_name) <> ''),
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    normalized_text TEXT NOT NULL CHECK (btrim(normalized_text) <> ''),
    chunk_size INTEGER NOT NULL CHECK (chunk_size BETWEEN 64 AND 16000),
    chunk_overlap INTEGER NOT NULL CHECK (chunk_overlap >= 0 AND chunk_overlap < chunk_size),
    chunk_count INTEGER NOT NULL CHECK (chunk_count > 0),
    UNIQUE (id, game_version_id)
);
CREATE INDEX rule_documents_by_version ON rule_documents (game_version_id, imported_at, id);

CREATE TABLE rule_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    game_version_id UUID NOT NULL,
    content TEXT NOT NULL CHECK (btrim(content) <> ''),
    section_title VARCHAR(300),
    page_number INTEGER CHECK (page_number > 0),
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    FOREIGN KEY (document_id, game_version_id) REFERENCES rule_documents(id, game_version_id),
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX rule_chunks_by_version ON rule_chunks (game_version_id);
