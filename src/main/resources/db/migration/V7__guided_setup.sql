CREATE TABLE setup_flows (
    id UUID PRIMARY KEY,
    game_version_id UUID NOT NULL REFERENCES game_versions(id),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, game_version_id)
);
CREATE INDEX setup_flow_version ON setup_flows(game_version_id, created_at DESC, id DESC);

CREATE TABLE setup_steps (
    id UUID PRIMARY KEY,
    setup_flow_id UUID NOT NULL REFERENCES setup_flows(id),
    step_key VARCHAR(80) NOT NULL CHECK (step_key ~ '^[A-Z][A-Z0-9_]*$'),
    step_order INTEGER NOT NULL CHECK (step_order >= 0),
    title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) > 0),
    instruction VARCHAR(8000) NOT NULL CHECK (length(btrim(instruction)) > 0),
    notes JSONB NOT NULL CHECK (jsonb_typeof(notes) = 'array'),
    warnings JSONB NOT NULL CHECK (jsonb_typeof(warnings) = 'array'),
    UNIQUE (setup_flow_id, step_key),
    UNIQUE (setup_flow_id, step_order)
);

ALTER TABLE teaching_sections ADD COLUMN setup_trigger BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX one_setup_trigger_per_teaching_flow ON teaching_sections(teaching_flow_id) WHERE setup_trigger;
ALTER TABLE teaching_sections ADD CONSTRAINT setup_trigger_all_depths CHECK (NOT setup_trigger
    OR depths @> '["QUICK","STANDARD","DETAILED"]'::jsonb);

ALTER TABLE game_sessions
    ADD COLUMN setup_flow_id UUID,
    ADD COLUMN current_setup_step_key VARCHAR(80),
    ADD COLUMN completed_setup_steps JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(completed_setup_steps) = 'array'),
    ADD COLUMN setup_return_section_key VARCHAR(80),
    ADD COLUMN setup_completed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT setup_session_version_fk FOREIGN KEY (setup_flow_id, game_version_id) REFERENCES setup_flows(id, game_version_id),
    ADD CONSTRAINT setup_current_step_fk FOREIGN KEY (setup_flow_id, current_setup_step_key) REFERENCES setup_steps(setup_flow_id, step_key),
    ADD CONSTRAINT setup_teaching_return_fk FOREIGN KEY (teaching_flow_id, setup_return_section_key) REFERENCES teaching_sections(teaching_flow_id, section_key),
    ADD CONSTRAINT setup_progress_valid CHECK (
        (setup_flow_id IS NOT NULL OR (current_setup_step_key IS NULL AND setup_return_section_key IS NULL
            AND NOT setup_completed AND completed_setup_steps = '[]'::jsonb))
        AND ((current_setup_step_key IS NULL) = (setup_return_section_key IS NULL))
        AND (current_setup_step_key IS NOT NULL OR (NOT setup_completed AND completed_setup_steps = '[]'::jsonb))
        AND (stage <> 'SETUP' OR (setup_flow_id IS NOT NULL AND current_setup_step_key IS NOT NULL AND NOT setup_completed))
        AND (NOT setup_completed OR stage <> 'SETUP'));
CREATE INDEX session_setup_flow_step ON game_sessions(setup_flow_id, current_setup_step_key);
CREATE INDEX session_setup_return ON game_sessions(teaching_flow_id, setup_return_section_key);

-- Original concise beginner guidance, checked against CATAN Game Rules & Almanac, pp. 2–4.
-- https://www.catan.com/sites/default/files/2021-06/catan_base_rules_2020_200707.pdf
-- No commercial diagrams or rulebook files are bundled. The physical beginner map is required.
INSERT INTO setup_flows(id, game_version_id, name, created_at)
VALUES ('00000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000002',
    'Catan base game beginner setup', CURRENT_TIMESTAMP);
INSERT INTO setup_steps(id, setup_flow_id, step_key, step_order, title, instruction, notes, warnings)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000007'::uuid, step_key, step_order, title, instruction, notes::jsonb, warnings::jsonb
FROM (VALUES
    ('BOARD', 0, 'Build the beginner board',
     'Match the frame ends, then arrange terrain and number tokens using the beginner map in your base-game rulebook. Put the robber on the desert.',
     '["Keep the beginner map open: this text does not reproduce its diagram."]', '[]'),
    ('PIECES', 1, 'Choose player colors',
     'Each player takes one color: five settlements, four cities, fifteen roads and a building-cost card.', '[]', '[]'),
    ('STARTING_POSITIONS', 2, 'Place starting pieces',
     'Place two settlements and two roads per player at the beginner-map positions. In a three-player game, leave the red positions unused.',
     '[]', '["Use the beginner layout throughout this walkthrough, rather than mixing in variable placement."]'),
    ('SUPPLIES', 3, 'Prepare shared supplies',
     'Separate resources into five face-up supplies. Shuffle development cards into a face-down deck. Put both special award cards and the dice beside the board.', '[]', '[]'),
    ('STARTING_RESOURCES', 4, 'Collect starting resources',
     'For each player, take one matching resource from each producing hex beside their star-marked starting settlement in the beginner diagram.',
     '[]', '["Follow the star-marked settlement in the diagram for this beginner layout."]'),
    ('FINAL_CHECK', 5, 'Confirm the table is ready',
     'Check the layout and supplies together. The oldest player starts this beginner game. Complete setup to return to your lesson.',
     '["Completion records your confirmation; the application cannot inspect the physical board."]', '[]')
) AS steps(step_key, step_order, title, instruction, notes, warnings);

-- Publish a replacement only when the current Catan teaching flow is the original V6 seed.
-- Existing sessions and any user-published teaching flows remain untouched.
INSERT INTO teaching_flows(id, game_version_id, name, created_at)
SELECT '00000000-0000-0000-0000-000000000070'::uuid, game_version_id,
    'Catan introductory lessons with guided setup', CURRENT_TIMESTAMP
FROM teaching_flows WHERE id = '00000000-0000-0000-0000-000000000006'
    AND id = (SELECT id FROM teaching_flows WHERE game_version_id='00000000-0000-0000-0000-000000000002' ORDER BY created_at DESC, id DESC LIMIT 1);
INSERT INTO teaching_sections(id, teaching_flow_id, section_key, section_order, title, explanation, depths, examples, tips, rule_references, setup_trigger)
SELECT gen_random_uuid(), target.id, old.section_key, old.section_order + CASE WHEN old.section_order >= 3 THEN 1 ELSE 0 END,
    old.title, CASE WHEN old.section_key = 'INTRODUCTION'
        THEN 'Gather resources, trade and build on Catan. We will learn one topic at a time and prepare the physical table during teaching.'
        WHEN old.section_key = 'WINNING'
        THEN 'Check your victory points on your turn. Reach 10 to win. You have completed the introductory lessons; ask verified rule questions as situations arise.'
        ELSE old.explanation END,
    old.depths, old.examples, old.tips, old.rule_references, FALSE
FROM teaching_sections old CROSS JOIN teaching_flows target
WHERE old.teaching_flow_id='00000000-0000-0000-0000-000000000006' AND target.id='00000000-0000-0000-0000-000000000070';
INSERT INTO teaching_sections(id, teaching_flow_id, section_key, section_order, title, explanation, depths, examples, tips, rule_references, setup_trigger)
SELECT gen_random_uuid(), id, 'PHYSICAL_SETUP', 3, 'Prepare the table',
    'Choose Continue to start guided physical setup. When setup is complete, return here and continue to the gameplay lessons.',
    '["QUICK","STANDARD","DETAILED"]'::jsonb, '[]'::jsonb, '[]'::jsonb, '["CATAN Game Rules & Almanac: beginner setup"]'::jsonb, TRUE
FROM teaching_flows WHERE id='00000000-0000-0000-0000-000000000070';
