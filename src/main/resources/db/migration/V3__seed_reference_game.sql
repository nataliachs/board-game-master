-- Metadata only: no rulebooks, rule content, or game-specific application logic.
-- 5th Edition / English is the selected reference configuration, not the latest edition.
INSERT INTO games (id, slug, name, publisher, min_players, max_players,
    estimated_duration_minutes, description, active)
VALUES ('00000000-0000-0000-0000-000000000001', 'catan', 'Catan', 'Catan Studio', 3, 4,
    90, 'Reference base game metadata. Rule ingestion and rules assistance are not implemented yet.', TRUE);

INSERT INTO game_versions (id, game_id, edition, version_label, language, release_year, active, default_version)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
    '5th Edition', 'base-game', 'en', NULL, TRUE, TRUE);
