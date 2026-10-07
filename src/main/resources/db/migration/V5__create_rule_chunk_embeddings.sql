-- Preserve text-only imports and allow independent vector spaces to coexist.
ALTER TABLE rule_chunks ADD CONSTRAINT rule_chunks_id_version_unique UNIQUE (id, game_version_id);

CREATE TABLE rule_chunk_embeddings (
    chunk_id UUID NOT NULL,
    game_version_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL CHECK (provider ~ '^[a-z0-9-]{1,50}$'),
    model VARCHAR(200) NOT NULL CHECK (btrim(model) <> ''),
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 1 AND 2000),
    embedding VECTOR NOT NULL,
    embedded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chunk_id, provider, model, dimensions),
    FOREIGN KEY (chunk_id, game_version_id) REFERENCES rule_chunks (id, game_version_id),
    CHECK (vector_dims(embedding) = dimensions),
    CHECK ((embedding <#> embedding) < 0)
);

-- Exact cosine search after ruleset/vector-space filtering. ANN is unnecessary at MVP scale.
CREATE INDEX rule_embeddings_by_version_profile
    ON rule_chunk_embeddings (game_version_id, provider, model, dimensions);
