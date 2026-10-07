CREATE TABLE games (
    id UUID PRIMARY KEY,
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    publisher VARCHAR(200),
    min_players INTEGER NOT NULL CHECK (min_players > 0),
    max_players INTEGER NOT NULL CHECK (max_players >= min_players),
    estimated_duration_minutes INTEGER NOT NULL CHECK (estimated_duration_minutes > 0),
    description VARCHAR(4000),
    active BOOLEAN NOT NULL,
    CONSTRAINT valid_game_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE game_versions (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES games(id),
    edition VARCHAR(100) NOT NULL CHECK (length(btrim(edition)) > 0),
    version_label VARCHAR(100) NOT NULL CHECK (length(btrim(version_label)) > 0),
    language VARCHAR(35) NOT NULL CHECK (language ~ '^[a-z]{2,3}(-[a-z0-9]{2,8})*$'),
    release_year INTEGER CHECK (release_year BETWEEN 1 AND 9999),
    active BOOLEAN NOT NULL,
    default_version BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT default_version_must_be_active CHECK (NOT default_version OR active)
);

-- The leading game_id also serves lookups and foreign-key access by game.
CREATE UNIQUE INDEX unique_game_version_identity
    ON game_versions (game_id, lower(edition), lower(version_label), language);
CREATE UNIQUE INDEX one_default_version_per_game
    ON game_versions (game_id) WHERE default_version;
