-- Optional presentation metadata for the game library.
-- cover_image_url holds an https:// URL or an application path such as /images/covers/catan.jpg.
-- Games without a cover show a generated initials tile in the UI.
ALTER TABLE games
    ADD COLUMN category VARCHAR(40) CHECK (category IS NULL OR length(btrim(category)) > 0),
    ADD COLUMN cover_image_url VARCHAR(500)
        CHECK (cover_image_url IS NULL OR cover_image_url ~ '^(https://|/)[^\s"''<>]+$');

UPDATE games SET category = 'Strategy' WHERE id = '00000000-0000-0000-0000-000000000001';
