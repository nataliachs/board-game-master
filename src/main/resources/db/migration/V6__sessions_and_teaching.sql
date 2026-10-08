-- Immutable, version-bound teaching content. New publications never rewrite existing sessions.
CREATE TABLE teaching_flows (
    id UUID PRIMARY KEY,
    game_version_id UUID NOT NULL REFERENCES game_versions(id),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, game_version_id)
);
CREATE INDEX teaching_flow_version ON teaching_flows(game_version_id, created_at DESC, id);

CREATE TABLE teaching_sections (
    id UUID PRIMARY KEY,
    teaching_flow_id UUID NOT NULL REFERENCES teaching_flows(id),
    section_key VARCHAR(80) NOT NULL CHECK (section_key ~ '^[A-Z][A-Z0-9_]*$'),
    section_order INTEGER NOT NULL CHECK (section_order >= 0),
    title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) > 0),
    explanation VARCHAR(8000) NOT NULL CHECK (length(btrim(explanation)) > 0),
    depths JSONB NOT NULL CHECK (jsonb_typeof(depths) = 'array' AND jsonb_array_length(depths) > 0
        AND depths <@ '["QUICK","STANDARD","DETAILED"]'::jsonb),
    examples JSONB NOT NULL CHECK (jsonb_typeof(examples) = 'array'),
    tips JSONB NOT NULL CHECK (jsonb_typeof(tips) = 'array'),
    rule_references JSONB NOT NULL CHECK (jsonb_typeof(rule_references) = 'array'),
    UNIQUE (teaching_flow_id, section_key),
    UNIQUE (teaching_flow_id, section_order)
);

CREATE TABLE game_sessions (
    id UUID PRIMARY KEY,
    game_version_id UUID NOT NULL REFERENCES game_versions(id),
    teaching_flow_id UUID NOT NULL,
    player_count INTEGER NOT NULL CHECK (player_count > 0),
    teaching_depth VARCHAR(10) NOT NULL CHECK (teaching_depth IN ('QUICK','STANDARD','DETAILED')),
    stage VARCHAR(12) NOT NULL CHECK (stage IN ('TEACHING','SETUP','PLAYING','COMPLETED')),
    status VARCHAR(12) NOT NULL CHECK (status IN ('ACTIVE','COMPLETED')),
    current_section_key VARCHAR(80) NOT NULL,
    completed_sections JSONB NOT NULL CHECK (jsonb_typeof(completed_sections) = 'array'),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    FOREIGN KEY (teaching_flow_id, game_version_id) REFERENCES teaching_flows(id, game_version_id),
    FOREIGN KEY (teaching_flow_id, current_section_key) REFERENCES teaching_sections(teaching_flow_id, section_key),
    CHECK ((status = 'COMPLETED') = (stage = 'COMPLETED')),
    CHECK ((status = 'COMPLETED') = (ended_at IS NOT NULL))
);
CREATE INDEX session_version ON game_sessions(game_version_id);
CREATE INDEX session_flow_section ON game_sessions(teaching_flow_id, current_section_key);

CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES game_sessions(id),
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    role VARCHAR(10) NOT NULL CHECK (role IN ('USER','ASSISTANT','SYSTEM')),
    content VARCHAR(16000) NOT NULL CHECK (length(btrim(content)) > 0),
    answer JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, sequence_number),
    CHECK (answer IS NULL OR (role = 'ASSISTANT' AND jsonb_typeof(answer) = 'object'))
);

-- Original, concise reference teaching data, not copied commercial rulebook text.
-- Physical setup is deliberately absent: its workflow belongs to Milestone 7.
INSERT INTO teaching_flows(id, game_version_id, name, created_at)
VALUES ('00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000002',
    'Catan base game introductory lessons', '2026-10-08T00:00:00Z');

INSERT INTO teaching_sections(id, teaching_flow_id, section_key, section_order, title, explanation, depths, examples, tips, rule_references)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000006'::uuid, section_key, section_order, title, explanation,
    depths::jsonb, examples::jsonb, '[]'::jsonb, rule_references::jsonb
FROM (VALUES
    ('INTRODUCTION', 0, 'Welcome to Catan',
     'You will gather resources, trade with others and grow a network of roads, settlements and cities. We will learn one topic at a time. Physical board setup guidance will be added separately.',
     '["QUICK","STANDARD","DETAILED"]', '[]', '["Base-game overview"]'),
    ('OBJECTIVE', 1, 'Your goal',
     'Reach 10 victory points on your turn to win. Settlements are worth one point and cities are worth two. Other point sources include certain development cards and the special awards.',
     '["QUICK","STANDARD","DETAILED"]', '["Upgrading a settlement to a city increases that location from one point to two."]', '["Victory points and ending the game"]'),
    ('COMPONENTS', 2, 'Know your pieces',
     'Look for your roads, settlements and cities. Resource cards represent brick, lumber, wool, grain and ore. The terrain hexes and number tokens determine where production happens; the robber can block a hex.',
     '["STANDARD","DETAILED"]', '[]', '["Components and terrain"]'),
    ('PRODUCTION', 3, 'Collecting resources',
     'At the start of a normal turn, roll both dice. Terrain hexes with the matching number produce for adjacent settlements and cities: one resource for a settlement, two for a city. A hex blocked by the robber produces nothing.',
     '["QUICK","STANDARD","DETAILED"]', '["If a forest with a settlement beside it produces, that settlement earns one lumber."]', '["Resource production"]'),
    ('TRADING', 4, 'Trading resources',
     'On your turn you may negotiate resource trades with other players, or use maritime trade. The usual bank rate is four matching resources for one resource of your choice; an eligible harbor can improve that rate.',
     '["STANDARD","DETAILED"]', '["Without a harbor benefit, four wool can be exchanged with the bank for one ore."]', '["Domestic and maritime trade"]'),
    ('BUILDING', 5, 'Building and developing',
     'Pay the required resources to build. A road costs brick and lumber. A settlement costs brick, lumber, wool and grain; leave at least two edges between settlements or cities. A city upgrade costs two grain and three ore. A development card costs wool, grain and ore. Connection and placement restrictions still apply.',
     '["QUICK","STANDARD","DETAILED"]', '["Keep the building-cost reference beside you while learning."]', '["Building costs and placement"]'),
    ('ROBBER', 6, 'When a seven is rolled',
     'A seven produces no resources. Each player holding more than seven resource cards discards half, rounded down. The active player moves the robber to a different terrain hex and may steal one random resource from an eligible adjacent opponent.',
     '["QUICK","STANDARD","DETAILED"]', '["A player holding nine resource cards discards four when a seven is rolled."]', '["Seven and robber"]'),
    ('TURN', 7, 'A normal turn',
     'Roll for production, then trade and build as allowed, and pass the turn. Development cards have timing restrictions and exceptions; ask a rule question when one becomes relevant rather than assuming every card can be played immediately.',
     '["QUICK","STANDARD","DETAILED"]', '[]', '["Turn sequence and development cards"]'),
    ('AWARDS', 8, 'Special point awards',
     'Longest Road and Largest Army can each be worth two victory points. Their qualification, ties and transfer rules matter; check the relevant rule source before claiming either award.',
     '["DETAILED"]', '[]', '["Longest Road and Largest Army"]'),
    ('WINNING', 9, 'Ready to begin',
     'Check your victory points on your turn. You win when you reach 10. You now have the introductory overview; keep the cost reference and verified rules nearby and ask questions as situations arise. This overview does not perform or verify physical setup.',
     '["QUICK","STANDARD","DETAILED"]', '[]', '["Ending the game"]')
) AS lessons(section_key, section_order, title, explanation, depths, examples, rule_references);
